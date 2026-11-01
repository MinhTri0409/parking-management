package com.doan.parking.repository;

import com.doan.parking.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Truy vấn bảng tài khoản. */
public interface UserRepository extends JpaRepository<AppUser, Long> {
    // Tìm tài khoản theo tên đăng nhập (dùng khi đăng nhập)
    Optional<AppUser> findByUsername(String username);

    List<AppUser> findByUsernameIn(Collection<String> usernames);

    List<AppUser> findByRoleOrderByUsernameAsc(String role);

    long countByRoleAndEnabledTrue(String role);
}
