package com.doan.parking.controller;

import com.doan.parking.entity.AppUser;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class CustomerAccountController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/register")
    public String register() {
        return "register";
    }

    @GetMapping("/customer/account")
    public String account() {
        return "customer-account";
    }

    @PostMapping("/register")
    public String createAccount(@RequestParam String username,
                                @RequestParam String fullName,
                                @RequestParam String phone,
                                @RequestParam String password,
                                @RequestParam String confirmPassword,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        String normalizedUsername = username.trim();
        String normalizedFullName = fullName.trim();
        String normalizedPhone = phone.trim();
        if (!normalizedUsername.matches("[A-Za-z0-9._-]{3,50}")) {
            model.addAttribute("error", "Tên đăng nhập cần từ 3 đến 50 ký tự (chữ, số, dấu chấm, gạch dưới hoặc gạch ngang)");
            return "register";
        }
        if (normalizedFullName.isBlank() || normalizedFullName.length() > 100) {
            model.addAttribute("error", "Vui lòng nhập họ tên, tối đa 100 ký tự");
            return "register";
        }
        if (!normalizedPhone.matches("[0-9+().\\- ]{7,20}")) {
            model.addAttribute("error", "Vui lòng nhập số điện thoại hợp lệ");
            return "register";
        }
        if (password.length() < 8) {
            model.addAttribute("error", "Mật khẩu cần có ít nhất 8 ký tự");
            return "register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Mật khẩu xác nhận không khớp");
            return "register";
        }
        if (userRepository.findByUsername(normalizedUsername).isPresent()) {
            model.addAttribute("error", "Tên đăng nhập đã được sử dụng");
            return "register";
        }

        AppUser customer = new AppUser();
        customer.setUsername(normalizedUsername);
        customer.setFullName(normalizedFullName);
        customer.setPhone(normalizedPhone);
        customer.setPassword(passwordEncoder.encode(password));
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        userRepository.save(customer);
        redirectAttributes.addFlashAttribute("registered", true);
        return "redirect:/login";
    }
}
