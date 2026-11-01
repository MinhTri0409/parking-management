package com.doan.parking.service;

import com.doan.parking.entity.AppUser;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Cho Spring Security biết cách lấy thông tin tài khoản từ CSDL khi đăng nhập. */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository repo;

    @Override
    // Tìm tài khoản theo tên đăng nhập; không thấy thì báo lỗi
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser a = repo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        String role = a.getRole().trim().toUpperCase(Locale.ROOT);
        if (role.startsWith("ROLE_")) {
            role = role.substring("ROLE_".length());
        }
        // Chuyển AppUser thành UserDetails (kèm quyền và trạng thái khóa)
        return User.withUsername(a.getUsername())
                .password(a.getPassword())
                .roles(role)
                .disabled(!a.isEnabled())
                .build();
    }
}
