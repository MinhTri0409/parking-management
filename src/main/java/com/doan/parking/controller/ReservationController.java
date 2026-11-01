package com.doan.parking.controller;

import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.UserRepository;
import com.doan.parking.service.ReservationService;
import com.doan.parking.service.CustomerNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.ArrayList;

@Controller
@RequiredArgsConstructor
public class ReservationController {
    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ReservationService reservationService;
    private final UserRepository userRepository;
    private final CustomerNotificationService notificationService;

    @GetMapping("/reservations")
    public String customerPage(@RequestParam(required = false) VehicleType type,
                               @RequestParam(required = false)
                               @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime start,
                               @RequestParam(required = false)
                               @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime end,
                               @RequestParam(required = false) String created,
                               @RequestParam(defaultValue = "0") int page,
                               Principal principal,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        VehicleType selectedType = type == null ? VehicleType.CAR : type;
        LocalDateTime selectedStart = start == null ? defaultStart() : start;
        LocalDateTime selectedEnd = end == null ? selectedStart.plusHours(2) : end;
        try {
            List<ReservationService.SlotOption> slots =
                    reservationService.availableSlots(selectedType, selectedStart, selectedEnd);
            model.addAttribute("slots", slots);
            model.addAttribute("availableSlotCount",
                    slots.stream().filter(ReservationService.SlotOption::available).count());
            model.addAttribute("estimatedAmount",
                    reservationService.quote(selectedType, selectedStart, selectedEnd));
        } catch (IllegalArgumentException | ArithmeticException e) {
            model.addAttribute("error", e.getMessage() == null ? "Thời gian đặt chỗ không hợp lệ" : e.getMessage());
            model.addAttribute("slots", List.of());
            model.addAttribute("estimatedAmount", 0L);
            model.addAttribute("availableSlotCount", 0L);
        }

        if (created != null && !created.isBlank()) {
            try {
                model.addAttribute("createdReservation",
                        reservationService.customerBooking(created, principal.getName()));
            } catch (IllegalArgumentException ignored) {
                redirectAttributes.addFlashAttribute("error", "Không tìm thấy đơn đặt chỗ vừa tạo");
                return "redirect:/reservations";
            }
        }
        model.addAttribute("types", VehicleType.values());
        model.addAttribute("selectedType", selectedType);
        model.addAttribute("hourlyRate", reservationService.hourlyRate(selectedType));
        model.addAttribute("startValue", selectedStart.format(INPUT_TIME));
        model.addAttribute("endValue", selectedEnd.format(INPUT_TIME));
        List<ParkingReservation> reservations = reservationService.customerBookings(principal.getName());
        Page<ParkingReservation> reservationPage = paginate(reservations, page);
        LocalDateTime now = LocalDateTime.now();
        model.addAttribute("reservations", reservationPage.getContent());
        model.addAttribute("reservationPage", reservationPage);
        model.addAttribute("paginationUrlPrefix", UriComponentsBuilder.fromPath("/reservations")
                .queryParam("type", selectedType.name())
                .queryParam("start", selectedStart.format(INPUT_TIME))
                .queryParam("end", selectedEnd.format(INPUT_TIME))
                .build().encode().toUriString() + "&page=");
        model.addAttribute("totalBookings", reservations.size());
        model.addAttribute("pendingPaymentCount", reservations.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.PENDING_PAYMENT).count());
        model.addAttribute("confirmedCount", reservations.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.CONFIRMED
                        || reservation.getStatus() == ReservationStatus.ARRIVED).count());
        model.addAttribute("completedCount", reservations.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.COMPLETED).count());
        model.addAttribute("upcomingBookings", reservations.stream()
                .filter(reservation -> (reservation.getStatus() == ReservationStatus.PENDING_PAYMENT
                        || reservation.getStatus() == ReservationStatus.CONFIRMED)
                        && reservation.getEndTime().isAfter(now))
                .sorted(Comparator.comparing(ParkingReservation::getStartTime))
                .limit(3)
                .toList());
        model.addAttribute("recentBookings", reservationPage.getContent());
        return "customer-dashboard";
    }

    @PostMapping("/reservations")
    public String create(@RequestParam String plate,
                         @RequestParam VehicleType type,
                         @RequestParam String slotCode,
                         @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime start,
                         @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime end,
                         Principal principal, RedirectAttributes redirectAttributes) {
        try {
            ParkingReservation reservation = reservationService.create(
                    principal.getName(), plate, type, slotCode, start, end);
            return "redirect:/reservations?created=" + reservation.getBookingCode();
        } catch (IllegalArgumentException | ArithmeticException e) {
            redirectAttributes.addFlashAttribute("error",
                    e.getMessage() == null ? "Không thể tạo đơn đặt chỗ" : e.getMessage());
            return "redirect:/reservations?type=" + type
                    + "&start=" + start.format(INPUT_TIME)
                    + "&end=" + end.format(INPUT_TIME);
        }
    }

    @PostMapping("/reservations/{id}/cancel")
    public String cancel(@PathVariable @NonNull Long id, Principal principal,
                         RedirectAttributes redirectAttributes) {
        try {
            reservationService.cancel(id, principal.getName());
            redirectAttributes.addFlashAttribute("success", "Đã hủy đơn đặt chỗ");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/reservations";
    }

    @GetMapping("/admin/reservations")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public String management(@RequestParam(defaultValue = "0") int page, Model model) {
        List<ParkingReservation> reservations = reservationService.allBookings();
        Page<ParkingReservation> reservationPage = paginate(reservations, page);
        reservations = reservationPage.getContent();
        List<String> usernames = reservations.stream()
                        .map(ParkingReservation::getCustomerUsername)
                        .distinct()
                        .toList();
        Map<String, String> customerPhones = usernames.isEmpty() ? Map.of()
                : userRepository.findByUsernameIn(usernames).stream()
                        .collect(Collectors.toMap(user -> user.getUsername(),
                                user -> user.getPhone() == null ? "" : user.getPhone()));
        model.addAttribute("reservations", reservations);
        model.addAttribute("reservationPage", reservationPage);
        model.addAttribute("paginationUrlPrefix", "/admin/reservations?page=");
        model.addAttribute("customerPhones", customerPhones);
        return "reservation-management";
    }

    private <T> Page<T> paginate(List<T> items, int requestedPage) {
        int pageSize = 10;
        int pageNumber = Math.max(requestedPage, 0);
        if (!items.isEmpty()) pageNumber = Math.min(pageNumber, (items.size() - 1) / pageSize);
        int fromIndex = Math.min(pageNumber * pageSize, items.size());
        int toIndex = Math.min(fromIndex + pageSize, items.size());
        return new PageImpl<>(new ArrayList<>(items.subList(fromIndex, toIndex)),
                PageRequest.of(pageNumber, pageSize), items.size());
    }

    @PostMapping("/admin/reservations/{id}/confirm-payment")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public String confirmPayment(@PathVariable @NonNull Long id,
                                 @RequestParam(defaultValue = "false") boolean paymentVerified,
                                 RedirectAttributes redirectAttributes) {
        try {
            if (!paymentVerified) {
                throw new IllegalArgumentException("Hãy xác nhận đã kiểm tra giao dịch ngân hàng thành công");
            }
            ParkingReservation reservation = reservationService.confirmPayment(id);
            notificationService.notifyCustomer(
                    reservation.getCustomerUsername(),
                    reservation.getId(),
                    "Đặt chỗ " + reservation.getBookingCode() + " đã được xác nhận thanh toán thành công.");
            redirectAttributes.addFlashAttribute("success", "Đã xác nhận thanh toán và đặt chỗ");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/reservations";
    }

    @GetMapping(value = "/reservations/payment/qr", produces = MediaType.IMAGE_JPEG_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> paymentQr() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("payment/mb-bank-qr.jpg"));
    }

    private LocalDateTime defaultStart() {
        return LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0);
    }
}
