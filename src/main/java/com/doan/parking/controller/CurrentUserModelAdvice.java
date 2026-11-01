package com.doan.parking.controller;

import com.doan.parking.entity.AppUser;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

@ControllerAdvice(annotations = Controller.class)
@RequiredArgsConstructor
public class CurrentUserModelAdvice {
    private final UserRepository userRepository;

    @ModelAttribute("currentUserProfile")
    public CurrentUserProfile currentUserProfile(Principal principal) {
        if (principal == null) {
            return null;
        }
        AppUser user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy tài khoản đang đăng nhập"));
        return new CurrentUserProfile(user.getId(), user.getUsername(), user.getFullName(), user.getPhone(), user.getRole(), user.isEnabled());
    }

    public static final class CurrentUserProfile {
        private final Long id;
        private final String username;
        private final String fullName;
        private final String phone;
        private final String role;
        private final boolean enabled;

        public CurrentUserProfile(@NonNull Long id, @NonNull String username, String fullName,
                                  String phone, @NonNull String role, boolean enabled) {
            this.id = id;
            this.username = username;
            this.fullName = fullName;
            this.phone = phone;
            this.role = role;
            this.enabled = enabled;
        }

        public Long getId() {
            return id;
        }

        public String getUsername() {
            return username;
        }

        public String getFullName() {
            return fullName;
        }

        public String getPhone() {
            return phone;
        }

        public String getRole() {
            return role;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }
}
