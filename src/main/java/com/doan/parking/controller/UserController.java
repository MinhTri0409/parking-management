package com.doan.parking.controller;

import com.doan.parking.entity.AppUser;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/** Quản lý tài khoản người dùng (chỉ ADMIN). */
@Controller
@RequiredArgsConstructor
public class UserController {
    private final UserRepository repo;
    private final ParkingRecordRepository recordRepo;
    private final PasswordEncoder encoder;

    // Danh sách tài khoản
    @GetMapping("/admin/users")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        int pageNumber = Math.max(page, 0);
        Page<AppUser> users = repo.findAll(PageRequest.of(pageNumber, 10, Sort.by("username").ascending()));
        if (users.getTotalPages() > 0 && pageNumber >= users.getTotalPages()) {
            users = repo.findAll(PageRequest.of(users.getTotalPages() - 1, 10, Sort.by("username").ascending()));
        }
        model.addAttribute("users", users);
        model.addAttribute("paginationUrlPrefix", "/admin/users?page=");
        return "users";
    }

    // Tạo tài khoản mới: kiểm tra dữ liệu và không cho trùng tên đăng nhập
    @PostMapping("/admin/users")
    public String add(@RequestParam String username, @RequestParam String password,
                      @RequestParam String fullName, @RequestParam String role, RedirectAttributes ra) {
        username = username.trim();
        if (username.isEmpty() || password.length() < 6 || !(role.equals("ADMIN") || role.equals("STAFF"))) {
            ra.addFlashAttribute("error", "Tên đăng nhập không được trống, mật khẩu tối thiểu 6 ký tự");
        } else if (repo.findByUsername(username).isPresent()) {
            ra.addFlashAttribute("error", "Tên đăng nhập đã tồn tại");
        } else {
            AppUser u = new AppUser();
            u.setUsername(username);
            u.setPassword(encoder.encode(password));
            u.setFullName(fullName.trim());
            u.setRole(role);
            repo.save(u);
            ra.addFlashAttribute("success", "Đã tạo tài khoản " + username);
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/password")
    public String changePassword(@PathVariable @NonNull Long id,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes ra) {
        if (newPassword.length() < 6) {
            ra.addFlashAttribute("error", "Mật khẩu mới phải có ít nhất 6 ký tự");
            return "redirect:/admin/users";
        }
        if (!newPassword.equals(confirmPassword)) {
            ra.addFlashAttribute("error", "Mật khẩu xác nhận không khớp");
            return "redirect:/admin/users";
        }

        AppUser user = repo.findById(id).orElseThrow();
        user.setPassword(encoder.encode(newPassword));
        repo.save(user);
        ra.addFlashAttribute("success", "Đã đổi mật khẩu cho tài khoản " + user.getUsername());
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/rename")
    @Transactional
    public String rename(@PathVariable @NonNull Long id,
                         @RequestParam String username,
                         @RequestParam(defaultValue = "") String fullName,
                         RedirectAttributes ra) {
        String newUsername = username.trim();
        String newFullName = fullName.trim();
        if (newUsername.isEmpty() || newUsername.length() > 50 || newFullName.length() > 100) {
            ra.addFlashAttribute("error", "Tên đăng nhập phải từ 1 đến 50 ký tự; họ tên tối đa 100 ký tự");
            return "redirect:/admin/users";
        }

        AppUser user = repo.findById(id).orElseThrow();
        if (repo.findByUsername(newUsername).filter(existing -> !existing.getId().equals(id)).isPresent()) {
            ra.addFlashAttribute("error", "Tên đăng nhập đã tồn tại");
            return "redirect:/admin/users";
        }

        String oldUsername = user.getUsername();
        if (!oldUsername.equals(newUsername)) {
            recordRepo.renameCheckInAccount(oldUsername, newUsername);
            recordRepo.renameCheckOutAccount(oldUsername, newUsername);
            user.setUsername(newUsername);
        }
        user.setFullName(newFullName);
        repo.save(user);
        ra.addFlashAttribute("success", "Đã cập nhật thông tin tài khoản " + newUsername
                + (oldUsername.equals(newUsername) ? "" : ". Nếu đang đăng nhập bằng tài khoản này, hãy đăng nhập lại."));
        return "redirect:/admin/users";
    }

    @PostMapping({"/admin/users/{id}/phone", "/users/{id}/phone"})
    public String updatePhone(@PathVariable @NonNull Long id,
                              @RequestParam(required = false) String phone,
                              @RequestParam(defaultValue = "false") boolean deletePhone,
                              @RequestParam(defaultValue = "") String returnTo,
                              Principal principal,
                              RedirectAttributes ra) {
        AppUser user = repo.findById(id).orElseThrow();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        boolean isStaff = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_STAFF"));

        if (!isAdmin && !isStaff && principal == null) {
            ra.addFlashAttribute("error", "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
            return "redirect:/login";
        }

        if (!isAdmin && !isStaff && !user.getUsername().equals(principal.getName())) {
            ra.addFlashAttribute("error", "Bạn chỉ có thể cập nhật số điện thoại của chính mình.");
            return "redirect:/reservations";
        }

        String normalizedPhone = phone == null ? "" : phone.trim();
        if (deletePhone || normalizedPhone.isEmpty()) {
            user.setPhone(null);
            repo.save(user);
            ra.addFlashAttribute("success", "Đã xóa số điện thoại của " + user.getUsername());
            return phoneReturnPath(isAdmin, isStaff, returnTo);
        }
        if (normalizedPhone.length() > 20) {
            ra.addFlashAttribute("error", "Số điện thoại tối đa 20 ký tự");
            return phoneReturnPath(isAdmin, isStaff, returnTo);
        }
        user.setPhone(normalizedPhone);
        repo.save(user);
        ra.addFlashAttribute("success", "Đã lưu số điện thoại cho " + user.getUsername());
        return phoneReturnPath(isAdmin, isStaff, returnTo);
    }

    private String phoneReturnPath(boolean isAdmin, boolean isStaff, String returnTo) {
        if (isAdmin) return "redirect:/admin/users";
        if (isStaff) return "redirect:/admin";
        return "redirect:" + switch (returnTo) {
            case "account" -> "/customer/account";
            case "dashboard" -> "/dashboard";
            default -> "/reservations";
        };
    }

    @PostMapping("/admin/users/{id}/delete")
    @Transactional
    public String delete(@PathVariable @NonNull Long id, Principal principal, RedirectAttributes ra) {
        AppUser user = repo.findById(id).orElseThrow();
        if (user.getUsername().equals(principal.getName())) {
            ra.addFlashAttribute("error", "Không thể xóa tài khoản đang đăng nhập");
            return "redirect:/admin/users";
        }
        if ("ADMIN".equals(user.getRole()) && user.isEnabled()
                && repo.countByRoleAndEnabledTrue("ADMIN") <= 1) {
            ra.addFlashAttribute("error", "Không thể xóa quản trị viên đang hoạt động cuối cùng");
            return "redirect:/admin/users";
        }

        repo.delete(user);
        ra.addFlashAttribute("success", "Đã xóa tài khoản " + user.getUsername()
                + ". Lịch sử xe vào/ra được giữ nguyên.");
        return "redirect:/admin/users";
    }

    // Khóa / mở khóa tài khoản (không được tự khóa chính mình)
    @PostMapping("/admin/users/{id}/toggle")
    public String toggle(@PathVariable @NonNull Long id, Principal principal, RedirectAttributes ra) {
        AppUser u = repo.findById(id).orElseThrow();
        if (u.getUsername().equals(principal.getName())) {
            ra.addFlashAttribute("error", "Không thể khóa chính tài khoản đang đăng nhập");
        } else {
            u.setEnabled(!u.isEnabled());
            repo.save(u);
            ra.addFlashAttribute("success", (u.isEnabled() ? "Đã mở khóa " : "Đã khóa ") + u.getUsername());
        }
        return "redirect:/admin/users";
    }
}
