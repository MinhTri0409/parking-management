package com.doan.parking.config;

import com.doan.parking.entity.AppUser;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.PricingRepository;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Tạo dữ liệu mặc định lần chạy đầu tiên. */
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final UserRepository userRepo;
    private final PricingRepository pricingRepo;
    private final PasswordEncoder encoder;

    @Override
    // Chạy một lần khi ứng dụng khởi động: tạo dữ liệu mặc định nếu CSDL còn trống
    public void run(String... args) {
        // Chưa có tài khoản nào thì tạo sẵn admin và staff
        if (userRepo.count() == 0) {
            userRepo.save(user("admin", "admin123", "Quản trị viên", "ADMIN"));
            userRepo.save(user("staff", "staff123", "Nhân viên bảo vệ", "STAFF"));
        }
        // Bổ sung giá mặc định cho loại xe còn thiếu mà không ghi đè cấu hình hiện có.
        ensurePricing(VehicleType.MOTORBIKE, 5000, 100000, 1000, 5000);
        ensurePricing(VehicleType.MOTORELECTRIC, 2000, 40000, 50, 2000);
        ensurePricing(VehicleType.CAR, 30000, 1000000, 50, 25000);
    }

    // Tạo đối tượng tài khoản, mật khẩu được mã hóa trước khi lưu
    @NonNull
    private AppUser user(String u, String p, String name, String role) {
        AppUser a = new AppUser();
        a.setUsername(u);
        a.setPassword(encoder.encode(p));
        a.setFullName(name);
        a.setRole(role);
        return a;
    }

    // Tạo đối tượng bảng giá cho một loại xe
    @NonNull
    private Pricing price(VehicleType t, long turn, long month, int cap, long hourly) {
        Pricing p = new Pricing();
        p.setVehicleType(t);
        p.setTurnPrice(turn);
        p.setMonthPrice(month);
        p.setCapacity(cap);
        p.setReservationHourlyPrice(hourly);
        return p;
    }

    private void ensurePricing(VehicleType type, long turn, long month, int capacity, long hourly) {
        Pricing pricing = pricingRepo.findByVehicleType(type).orElseGet(() -> price(type, turn, month, capacity, hourly));
        if (pricing.getReservationHourlyPrice() == null) {
            pricing.setReservationHourlyPrice(hourly);
        }
        pricingRepo.save(pricing);
    }
}
