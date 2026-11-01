package com.doan.parking.controller;

import com.doan.parking.service.ParkingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/** Trang tổng quan (trang chủ). */
@Controller
@RequiredArgsConstructor
public class DashboardController {
    private final ParkingService service;

    // Hiển thị sơ đồ bãi xe và số liệu vận hành cho nhân viên/quản trị viên
    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public String home(Model model) {
        List<ParkingService.ParkingSection> sections = service.parkingMap();
        long occupied = sections.stream().mapToLong(section -> section.stats().used()).sum();
        long capacity = sections.stream().mapToLong(section -> section.stats().capacity()).sum();
        model.addAttribute("parkingSections", sections);
        model.addAttribute("overflowMonthlyTicketCount", sections.stream()
                .flatMap(section -> section.overflowRecords().stream())
                .filter(record -> record.isMonthlyTicket() && record.getCheckOut() == null)
                .count());
        model.addAttribute("occupiedCount", occupied);
        model.addAttribute("availableCount", sections.stream()
                .flatMap(section -> section.slots().stream())
                .filter(slot -> !slot.occupied() && !slot.reserved())
                .count());
        model.addAttribute("totalCapacity", capacity);
        model.addAttribute("todayCount", service.todayCheckIns());
        model.addAttribute("todayRevenue", service.todayRevenue());
        return "dashboard";
    }

    @GetMapping("/staff/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public String legacyDashboard() {
        return "redirect:/admin";
    }
}
