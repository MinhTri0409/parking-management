package com.doan.parking.controller;

import com.doan.parking.entity.Pricing;
import com.doan.parking.repository.PricingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Quản lý bảng giá và sức chứa (chỉ ADMIN). */
@Controller
@RequiredArgsConstructor
public class PricingController {
    private final PricingRepository repo;

    // Hiển thị bảng giá và sức chứa
    @GetMapping("/admin/pricing")
    public String list(Model model) {
        model.addAttribute("prices", repo.findAll());
        return "pricing";
    }

    // Cập nhật giá và sức chứa của một loại xe
    @PostMapping("/admin/pricing/{id}")
    public String update(@PathVariable @NonNull Long id, @RequestParam long turnPrice,
                         @RequestParam long monthPrice, @RequestParam int capacity,
                         @RequestParam long reservationHourlyPrice, RedirectAttributes ra) {
        if (turnPrice < 0 || monthPrice < 0 || capacity < 0 || reservationHourlyPrice <= 0) {
            ra.addFlashAttribute("error", "Giá và sức chứa không được âm; giá đặt chỗ theo giờ phải lớn hơn 0");
            return "redirect:/admin/pricing";
        }
        Pricing p = repo.findById(id).orElseThrow();
        p.setTurnPrice(turnPrice);
        p.setMonthPrice(monthPrice);
        p.setCapacity(capacity);
        p.setReservationHourlyPrice(reservationHourlyPrice);
        repo.save(p);
        ra.addFlashAttribute("success", "Đã cập nhật bảng giá " + p.getVehicleType().getLabel());
        return "redirect:/admin/pricing";
    }
}
