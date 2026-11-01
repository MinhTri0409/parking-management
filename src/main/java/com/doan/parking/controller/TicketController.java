package com.doan.parking.controller;

import com.doan.parking.entity.MonthlyTicket;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.MonthlyTicketRepository;
import com.doan.parking.repository.PricingRepository;
import com.doan.parking.service.ParkingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Quản lý vé tháng: xem, tìm kiếm, đăng ký, cập nhật và hủy. */
@Controller
@RequiredArgsConstructor
public class TicketController {
    private final MonthlyTicketRepository ticketRepo;
    private final PricingRepository pricingRepo;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Danh sách vé tháng, có thể tìm theo biển số
    @GetMapping("/admin/tickets")
    public String list(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        List<TicketGroup> groups = groupTickets(
                ticketRepo.findByLicensePlateContainingIgnoreCaseOrderByEndDateDesc(q.trim()), LocalDate.now());
        int pageNumber = Math.max(page, 0);
        int pageSize = 10;
        if (!groups.isEmpty()) pageNumber = Math.min(pageNumber, (groups.size() - 1) / pageSize);
        int fromIndex = Math.min(pageNumber * pageSize, groups.size());
        int toIndex = Math.min(fromIndex + pageSize, groups.size());
        Page<TicketGroup> ticketGroups = new PageImpl<>(groups.subList(fromIndex, toIndex),
                PageRequest.of(pageNumber, pageSize), groups.size());
        String paginationUrlPrefix = UriComponentsBuilder.fromPath("/admin/tickets")
                .queryParam("q", q.trim()).build().encode().toUriString() + "&page=";
        model.addAttribute("ticketGroups", ticketGroups);
        model.addAttribute("paginationUrlPrefix", paginationUrlPrefix);
        model.addAttribute("types", VehicleType.values());
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("q", q);
        return "tickets";
    }

    // Đăng ký vé tháng mới: giá = giá vé tháng x số tháng
    @PostMapping("/admin/tickets")
    public String add(@RequestParam String licensePlate, @RequestParam String ownerName,
                      @RequestParam(defaultValue = "") String phone, @RequestParam VehicleType vehicleType,
                      @RequestParam(defaultValue = "1") int months, RedirectAttributes ra) {
        String plate = ParkingService.normalize(licensePlate);
        String normalizedOwnerName = ownerName == null ? "" : ownerName.trim();
        // Kiểm tra dữ liệu nhập vào
        if (plate.isEmpty() || plate.length() > 20 || normalizedOwnerName.isEmpty()
                || normalizedOwnerName.length() > 100 || months < 1 || months > 24) {
            ra.addFlashAttribute("error", "Thông tin vé tháng không hợp lệ (số tháng từ 1 đến 24)");
            return "redirect:/admin/tickets";
        }
        LocalDate today = LocalDate.now();
        LocalDate endDate = today.plusMonths(months);
        if (ticketRepo.existsByLicensePlateIgnoreCaseAndVehicleTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCancelledAtIsNull(
                plate, vehicleType, endDate, today)) {
            ra.addFlashAttribute("error",
                    "Xe " + plate + " đã có vé tháng cùng loại còn hạn giao với thời gian đăng ký. Không thể tạo vé trùng.");
            return "redirect:/admin/tickets";
        }
        Pricing p = pricingRepo.findByVehicleType(vehicleType).orElseThrow();
        MonthlyTicket t = new MonthlyTicket();
        t.setLicensePlate(plate);
        t.setOwnerName(normalizedOwnerName);
        t.setPhone(phone.trim());
        t.setVehicleType(vehicleType);
        t.setStartDate(today);
        // Hạn dùng = hôm nay cộng thêm số tháng đăng ký
        t.setEndDate(endDate);
        t.setPrice(p.getMonthPrice() * months);
        ticketRepo.save(t);
        ra.addFlashAttribute("success", "Đã đăng ký vé tháng cho xe " + plate);
        return "redirect:/admin/tickets";
    }

    private List<TicketGroup> groupTickets(List<MonthlyTicket> tickets, LocalDate today) {
        Map<String, List<MonthlyTicket>> byPlateAndType = new TreeMap<>();
        List<TicketGroup> groups = new ArrayList<>();
        for (MonthlyTicket ticket : tickets) {
            if (ticket.getCancelledAt() != null || ticket.getStartDate() == null || ticket.getEndDate() == null
                    || ticket.getEndDate().isBefore(ticket.getStartDate())) {
                groups.add(toTicketGroup(List.of(ticket), today));
                continue;
            }
            String key = ticket.getLicensePlate().toUpperCase(java.util.Locale.ROOT)
                    + "\u0000" + ticket.getVehicleType().name();
            byPlateAndType.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ticket);
        }

        for (List<MonthlyTicket> matchingTickets : byPlateAndType.values()) {
            matchingTickets.sort(Comparator.comparing(MonthlyTicket::getStartDate)
                    .thenComparing(MonthlyTicket::getEndDate));
            List<MonthlyTicket> overlapping = new ArrayList<>();
            LocalDate currentEnd = null;
            for (MonthlyTicket ticket : matchingTickets) {
                if (currentEnd == null || !ticket.getStartDate().isAfter(currentEnd)) {
                    overlapping.add(ticket);
                    if (currentEnd == null || ticket.getEndDate().isAfter(currentEnd)) {
                        currentEnd = ticket.getEndDate();
                    }
                } else {
                    groups.add(toTicketGroup(overlapping, today));
                    overlapping = new ArrayList<>();
                    overlapping.add(ticket);
                    currentEnd = ticket.getEndDate();
                }
            }
            if (!overlapping.isEmpty()) groups.add(toTicketGroup(overlapping, today));
        }

        groups.sort(Comparator.comparing(TicketGroup::latestEndDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return groups;
    }

    private TicketGroup toTicketGroup(List<MonthlyTicket> tickets, LocalDate today) {
        LocalDate earliestStart = tickets.stream().map(MonthlyTicket::getStartDate)
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate latestEnd = tickets.stream().map(MonthlyTicket::getEndDate)
                .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        long totalPrice = tickets.stream().mapToLong(MonthlyTicket::getPrice).sum();
        long usedDays = 0;
        long remainingDays = 0;
        long refundAmount = 0;
        LocalDate usageReferenceDate = tickets.stream()
                .filter(ticket -> ticket.getCancelledAt() != null)
                .map(ticket -> ticket.getCancelledAt().toLocalDate())
                .findFirst()
                .orElse(today);

        List<MonthlyTicket> datedTickets = tickets.stream()
                .filter(ticket -> ticket.getStartDate() != null && ticket.getEndDate() != null
                        && !ticket.getEndDate().isBefore(ticket.getStartDate()))
                .sorted(Comparator.comparing(MonthlyTicket::getStartDate)).toList();
        LocalDate intervalStart = null;
        LocalDate intervalEnd = null;
        for (MonthlyTicket ticket : datedTickets) {
            if (intervalEnd == null || ticket.getStartDate().isAfter(intervalEnd)) {
                if (intervalStart != null) {
                    long[] days = splitDays(intervalStart, intervalEnd, usageReferenceDate);
                    usedDays += days[0];
                    remainingDays += days[1];
                }
                intervalStart = ticket.getStartDate();
                intervalEnd = ticket.getEndDate();
            } else if (ticket.getEndDate().isAfter(intervalEnd)) {
                intervalEnd = ticket.getEndDate();
            }

            if (ticket.getCancelledAt() != null) {
                refundAmount += ticket.getRefundAmount();
            } else {
                refundAmount += calculateRefund(ticket, today);
            }
        }
        if (intervalStart != null) {
            long[] days = splitDays(intervalStart, intervalEnd, usageReferenceDate);
            usedDays += days[0];
            remainingDays += days[1];
        }

        boolean cancelled = tickets.stream().allMatch(ticket -> ticket.getCancelledAt() != null);
        boolean active = tickets.stream().anyMatch(ticket -> ticket.getCancelledAt() == null
                && ticket.getStartDate() != null && ticket.getEndDate() != null
                && !today.isBefore(ticket.getStartDate()) && !today.isAfter(ticket.getEndDate()));
        boolean renewalCancelled = tickets.stream().filter(ticket -> ticket.getCancelledAt() == null)
                .allMatch(MonthlyTicket::isRenewalCancelled);
        return new TicketGroup(tickets, earliestStart, latestEnd, totalPrice, usedDays,
                remainingDays, refundAmount, cancelled, active, renewalCancelled);
    }

    private long[] splitDays(LocalDate start, LocalDate end, LocalDate today) {
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1;
        long remaining = today.isAfter(end) ? 0
                : ChronoUnit.DAYS.between(today.isBefore(start) ? start : today, end) + 1;
        return new long[]{totalDays - remaining, remaining};
    }

    private long calculateRefund(MonthlyTicket ticket, LocalDate today) {
        if (ticket.getStartDate() == null || ticket.getEndDate() == null
                || ticket.getEndDate().isBefore(ticket.getStartDate())) {
            return 0;
        }
        long totalDays = ChronoUnit.DAYS.between(ticket.getStartDate(), ticket.getEndDate()) + 1;
        long remainingDays = today.isAfter(ticket.getEndDate()) ? 0
                : ChronoUnit.DAYS.between(today.isBefore(ticket.getStartDate()) ? ticket.getStartDate() : today,
                        ticket.getEndDate()) + 1;
        return BigDecimal.valueOf(ticket.getPrice()).multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public record TicketGroup(List<MonthlyTicket> tickets, LocalDate earliestStartDate, LocalDate latestEndDate,
                              long totalPrice, long usedDays, long remainingDays, long refundAmount,
                              boolean cancelled, boolean active, boolean renewalCancelled) {
        public MonthlyTicket primaryTicket() {
            return tickets.get(0);
        }

        public String statusLabel() {
            if (cancelled) return "Đã hủy vé";
            if (active) return renewalCancelled ? "Đã ngừng gia hạn" : "Còn hạn";
            return "Hết hạn";
        }
    }

    @PostMapping("/admin/tickets/{id}/rename")
    public String rename(@PathVariable @NonNull Long id,
                         @RequestParam String licensePlate,
                         @RequestParam String ownerName,
                         RedirectAttributes ra) {
        String plate = ParkingService.normalize(licensePlate);
        String name = ownerName == null ? "" : ownerName.trim();
        if (plate.isEmpty() || plate.length() > 20 || name.isEmpty() || name.length() > 100) {
            ra.addFlashAttribute("error", "Biển số phải từ 1 đến 20 ký tự và tên chủ xe từ 1 đến 100 ký tự");
            return "redirect:/admin/tickets";
        }

        MonthlyTicket ticket = ticketRepo.findById(id).orElse(null);
        if (ticket == null) {
            ra.addFlashAttribute("error", "Không tìm thấy vé tháng cần cập nhật");
            return "redirect:/admin/tickets";
        }
        ticket.setLicensePlate(plate);
        ticket.setOwnerName(name);
        ticketRepo.save(ticket);
        ra.addFlashAttribute("success", "Đã cập nhật biển số và tên chủ xe");
        return "redirect:/admin/tickets";
    }

    @PostMapping("/admin/tickets/{id}/cancel-renewal")
    public String cancelRenewal(@PathVariable @NonNull Long id, RedirectAttributes ra) {
        MonthlyTicket ticket = ticketRepo.findById(id).orElse(null);
        if (ticket == null) {
            ra.addFlashAttribute("error", "Không tìm thấy vé tháng cần hủy gia hạn");
            return "redirect:/admin/tickets";
        }
        if (ticket.getCancelledAt() != null) {
            ra.addFlashAttribute("error", "Vé này đã bị hủy ngay và không thể đổi trạng thái gia hạn");
            return "redirect:/admin/tickets";
        }
        if (ticket.isRenewalCancelled()) {
            ra.addFlashAttribute("error", "Vé tháng này đã được ngừng gia hạn");
            return "redirect:/admin/tickets";
        }
        if (ticket.getEndDate() == null) {
            ra.addFlashAttribute("error", "Vé tháng chưa có ngày hết hạn hợp lệ");
            return "redirect:/admin/tickets";
        }

        ticket.setRenewalCancelled(true);
        ticketRepo.save(ticket);
        ra.addFlashAttribute("success", "Đã ghi nhận ngừng gia hạn. Vé vẫn sử dụng đến hết ngày "
                + ticket.getEndDate().format(DATE_FORMAT)
                + ". Hệ thống hiện không tự động thu phí hoặc gia hạn vé.");
        return "redirect:/admin/tickets";
    }

    @PostMapping("/admin/tickets/{id}/cancel")
    public String cancel(@PathVariable @NonNull Long id, RedirectAttributes ra) {
        MonthlyTicket ticket = ticketRepo.findById(id).orElse(null);
        if (ticket == null) {
            ra.addFlashAttribute("error", "Không tìm thấy vé tháng cần hủy");
            return "redirect:/admin/tickets";
        }
        if (ticket.getCancelledAt() != null) {
            ra.addFlashAttribute("error", "Vé tháng này đã được hủy");
            return "redirect:/admin/tickets";
        }
        if (ticket.getStartDate() == null || ticket.getEndDate() == null || ticket.getEndDate().isBefore(ticket.getStartDate())) {
            ra.addFlashAttribute("error", "Không thể tính tiền hoàn vì thời hạn vé không hợp lệ");
            return "redirect:/admin/tickets";
        }

        LocalDate today = LocalDate.now();
        long totalDays = ChronoUnit.DAYS.between(ticket.getStartDate(), ticket.getEndDate()) + 1;
        long remainingDays = today.isAfter(ticket.getEndDate())
                ? 0
                : ChronoUnit.DAYS.between(today.isBefore(ticket.getStartDate()) ? ticket.getStartDate() : today,
                        ticket.getEndDate()) + 1;
        long refundAmount = BigDecimal.valueOf(ticket.getPrice())
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP)
                .longValueExact();

        ticket.setRenewalCancelled(true);
        ticket.setCancelledAt(LocalDateTime.now());
        ticket.setRefundAmount(refundAmount);
        ticketRepo.save(ticket);
        ra.addFlashAttribute("success", "Đã hủy vé tháng. Số tiền cần hoàn theo số ngày chưa sử dụng: "
                + String.format("%,d", refundAmount).replace(',', '.')
                + " đ. Nhân viên vui lòng hoàn tiền thủ công; hệ thống không tự chuyển tiền.");
        return "redirect:/admin/tickets";
    }

    // Xóa vé tháng (chỉ ADMIN, đã chặn trong SecurityConfig)
    @PostMapping("/admin/tickets/{id}/delete")
    public String delete(@PathVariable @NonNull Long id, RedirectAttributes ra) {
        ticketRepo.deleteById(id);
        ra.addFlashAttribute("success", "Đã xóa vé tháng");
        return "redirect:/admin/tickets";
    }
}
