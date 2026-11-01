package com.doan.parking.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Hiển thị trang đăng nhập (việc xác thực do Spring Security xử lý). */
@Controller
public class AuthController {
    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return "login";
        }
        if (authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_CUSTOMER"))) {
            return "redirect:/dashboard";
        }
        return "redirect:/admin";
    }
}
