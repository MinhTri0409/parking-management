package com.doan.parking.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Tài khoản đăng nhập hệ thống (bảng users). Mật khẩu được mã hóa bằng BCrypt. */
@Entity
@Table(name = "users")
@Getter @Setter
public class AppUser {
    // Khóa chính, tự tăng
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // Tên đăng nhập (không được trùng)
    @Column(nullable = false, unique = true, length = 50)
    private String username;
    // Mật khẩu đã mã hóa, không lưu mật khẩu thô
    @Column(nullable = false, length = 100)
    private String password;
    @Column(length = 100)
    private String fullName;
    @Column(nullable = false, length = 20)
    private String role; // ADMIN hoặc STAFF
    @Column(length = 20)
    private String phone;
    // true = đang hoạt động, false = bị khóa
    private boolean enabled = true;
    @Column(name = "support_chat_blocked", nullable = false)
    private boolean supportChatBlocked;
}
