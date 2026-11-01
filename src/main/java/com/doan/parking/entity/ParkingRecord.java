package com.doan.parking.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Một lượt gửi xe: ghi lại giờ vào, giờ ra và số tiền thu. */
@Entity
@Getter @Setter
public class ParkingRecord {
    // Khóa chính, tự tăng
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String licensePlate;
    @Column(length = 30)
    private String slotCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private VehicleType vehicleType;
    @Column(nullable = false)
    private LocalDateTime checkIn;
    private LocalDateTime checkOut; // null = xe còn trong bãi
    // Số tiền thu (VNĐ), bằng 0 nếu xe có vé tháng
    private long fee;
    // true nếu xe có vé tháng còn hạn lúc vào bãi
    private boolean monthlyTicket;
    @Column(length = 50)
    // Tên đăng nhập của nhân viên cho xe vào
    private String createdBy;
    @Column(length = 50)
    // Tên đăng nhập của nhân viên cho xe ra
    private String checkedOutBy;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentMethod paymentMethod;
}
