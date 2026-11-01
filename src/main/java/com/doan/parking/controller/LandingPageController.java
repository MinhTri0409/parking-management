package com.doan.parking.controller;

import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.PricingRepository;
import com.doan.parking.service.ParkingService;
import com.doan.parking.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class LandingPageController {
    private final PricingRepository pricingRepository;
    private final ParkingService parkingService;
    private final ReservationService reservationService;

    @GetMapping({"/", "/dashboard"})
    public String home(Model model, Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")
                            || authority.getAuthority().equals("ROLE_STAFF"))) {
                return "redirect:/admin";
            }
            if (request.getRequestURI().equals("/") && authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_CUSTOMER"))) {
                return "redirect:/dashboard";
            }
        }
        List<ParkingService.TypeStat> occupancy = parkingService.occupancy();
        LocalDateTime nextHour = LocalDateTime.now().plusSeconds(1);
        model.addAttribute("prices", pricingRepository.findAll());
        model.addAttribute("totalCapacity",
                occupancy.stream().mapToLong(ParkingService.TypeStat::capacity).sum());
        model.addAttribute("availableSpaces",
                java.util.Arrays.stream(VehicleType.values())
                        .mapToLong(type -> reservationService.availableSlots(type, nextHour, nextHour.plusHours(1))
                                .stream().filter(ReservationService.SlotOption::available).count()).sum());
        return "landing";
    }
}
