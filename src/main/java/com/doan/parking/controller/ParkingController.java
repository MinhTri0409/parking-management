package com.doan.parking.controller;

import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.PaymentMethod;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.MonthlyTicketRepository;
import com.doan.parking.repository.UserRepository;
import com.doan.parking.service.ParkingService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/** Xử lý xe vào/ra, danh sách xe trong bãi và lịch sử gửi xe. */
@Controller
@RequiredArgsConstructor
public class ParkingController {
    private final ParkingService service;
    private final UserRepository userRepository;
    private final MonthlyTicketRepository ticketRepository;

    // Trang danh sách xe đang trong bãi
    @GetMapping("/admin/parking")
    public String parking(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("records", service.inLot(page));
        model.addAttribute("paginationUrlPrefix", "/admin/parking?page=");
        model.addAttribute("types", VehicleType.values());
        return "parking";
    }

    // Xử lý form cho xe vào; lỗi nghiệp vụ được báo lại bằng thông báo đỏ
    @PostMapping("/admin/parking/checkin")
    public String checkIn(@RequestParam String plate, @RequestParam VehicleType type,
                          @RequestParam(required = false) String slotCode,
                          @RequestParam(required = false) String returnTo,
                          Principal principal, RedirectAttributes ra) {
        try {
            ParkingRecord r = service.checkIn(plate, type, principal.getName(), slotCode);
            ra.addFlashAttribute("success", "Xe " + r.getLicensePlate() + " đã vào bãi"
                    + " tại vị trí " + r.getSlotCode()
                    + (r.isMonthlyTicket() ? " (có vé tháng - miễn phí)" : ""));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return dashboardOrParking(returnTo);
    }

    // Xử lý cho xe ra và hiển thị số tiền thu
    @PostMapping("/admin/parking/checkout/{id}")
    public String checkOut(@PathVariable @NonNull Long id,
                           @RequestParam(required = false) String returnTo,
                           Principal principal,
                           RedirectAttributes ra) {
        try {
            ParkingService.CheckoutQuote quote = service.checkoutQuote(id);
            boolean needsPayment = quote.prepaidReservation()
                    ? quote.amount() > 0
                    : !quote.record().isMonthlyTicket();
            if (needsPayment) {
                return "redirect:/admin/parking/checkout/" + id + "?returnTo="
                        + ("dashboard".equals(returnTo) ? "dashboard" : "parking");
            }
            ParkingRecord r = service.checkOut(id, principal.getName(), null);
            ra.addFlashAttribute("success", (quote.prepaidReservation()
                    ? "Đặt chỗ đã thanh toán trước, không thu thêm. Xe "
                    : "Xe ") + r.getLicensePlate() + " ra bãi. Thu: "
                    + String.format("%,d", r.getFee()).replace(',', '.') + " đ");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return dashboardOrParking(returnTo);
    }

    @PostMapping("/admin/parking/checkout-overflow-monthly")
    public String checkOutOverflowMonthlyTickets(Principal principal, RedirectAttributes ra) {
        try {
            int checkedOutCount = service.checkOutOverflowMonthlyTickets(principal.getName());
            if (checkedOutCount == 0) {
                ra.addFlashAttribute("error", "Không có xe vượt sức chứa nào được miễn phí để check-out");
            } else {
                ra.addFlashAttribute("success", "Đã check-out " + checkedOutCount
                        + " xe vé tháng vượt sức chứa. Các xe phải thanh toán vẫn cần xử lý riêng.");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }

    @GetMapping("/admin/parking/checkout/{id}")
    public String payment(@PathVariable @NonNull Long id,
                          @RequestParam(required = false) String returnTo,
                          Model model,
                          RedirectAttributes ra) {
        try {
            ParkingService.CheckoutQuote quote = service.checkoutQuote(id);
            if (quote.record().isMonthlyTicket() && !quote.prepaidReservation()) {
                ra.addFlashAttribute("error", "Lượt xe này không cần thanh toán");
                return dashboardOrParking(returnTo);
            }
            if (quote.prepaidReservation() && quote.amount() == 0) {
                ra.addFlashAttribute("success", "Đặt chỗ " + quote.bookingCode()
                        + " đã thanh toán trước. Khách không cần trả thêm khi trả xe đúng giờ.");
                return dashboardOrParking(returnTo);
            }
            model.addAttribute("record", quote.record());
            model.addAttribute("amount", quote.amount());
            model.addAttribute("prepaidReservation", quote.prepaidReservation());
            model.addAttribute("bookingCode", quote.bookingCode());
            model.addAttribute("reservedUntil", quote.reservedUntil());
            model.addAttribute("extraHours", quote.extraHours());
            model.addAttribute("hourlyRate", quote.hourlyRate());
            model.addAttribute("returnTo", "dashboard".equals(returnTo) ? "dashboard" : "parking");
            model.addAttribute("paymentMethods", List.of(
                    PaymentMethod.QR, PaymentMethod.CASH, PaymentMethod.CARD));
            return "parking-payment";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return dashboardOrParking(returnTo);
        }
    }

    @GetMapping(value = "/admin/parking/payment/qr", produces = MediaType.IMAGE_JPEG_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> paymentQr() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("payment/mb-bank-qr.jpg"));
    }

    @PostMapping("/admin/parking/checkout/{id}/confirm")
    public String confirmPayment(@PathVariable @NonNull Long id,
                                 @RequestParam(required = false) String returnTo,
                                 @RequestParam(required = false) PaymentMethod paymentMethod,
                                 @RequestParam(defaultValue = "false") boolean paymentConfirmed,
                                 Principal principal,
                                 RedirectAttributes ra) {
        try {
            ParkingService.CheckoutQuote quote = service.checkoutQuote(id);
            boolean needsPayment = quote.prepaidReservation()
                    ? quote.amount() > 0
                    : !quote.record().isMonthlyTicket();
            if (needsPayment && (!paymentConfirmed || paymentMethod == null)) {
                throw new IllegalArgumentException("Vui lòng xác nhận đã hoàn tất thanh toán theo phương thức đã chọn");
            }
            ParkingRecord r = service.checkOut(id, principal.getName(), paymentMethod);
            String paymentMessage = r.getPaymentMethod() == null
                    ? "Đặt chỗ đã thanh toán trước, không thu thêm. "
                    : "Đã ghi nhận thanh toán " + r.getPaymentMethod().getLabel() + ". ";
            ra.addFlashAttribute("success", paymentMessage
                    + "Xe " + r.getLicensePlate()
                    + " ra bãi. Thu: " + String.format("%,d", r.getFee()).replace(',', '.') + " đ");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return dashboardOrParking(returnTo);
    }

    private String dashboardOrParking(String returnTo) {
        return "dashboard".equals(returnTo) ? "redirect:/admin" : "redirect:/admin/parking";
    }

    // Lịch sử cho phép ADMIN lọc riêng các lượt xe do nhân viên cho ra.
    @GetMapping("/admin/parking/history")
    public String history(@RequestParam(defaultValue = "") String plate,
                          @RequestParam(required = false) VehicleType type,
                          @RequestParam(required = false) String checkoutEmployee,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "0") int page,
                          Authentication authentication, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!isAdmin) checkoutEmployee = null;
        model.addAttribute("page", service.search(plate, type, checkoutEmployee, from, to, page));
        model.addAttribute("monthlyTickets", plate.isBlank()
                ? List.of()
                : ticketRepository.findByLicensePlateContainingIgnoreCaseOrderByEndDateDesc(plate.trim()));
        model.addAttribute("plate", plate);
        model.addAttribute("type", type);
        model.addAttribute("checkoutEmployee", checkoutEmployee);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("employees", isAdmin ? userRepository.findByRoleOrderByUsernameAsc("STAFF")
                : List.of());
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("types", VehicleType.values());
        return "history";
    }
}
