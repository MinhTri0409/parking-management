package com.doan.parking.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Vé tháng của một xe: xe có vé còn hạn được gửi miễn phí. */
@Entity
@Getter @Setter
public class MonthlyTicket {
    // Khóa chính, tự tăng
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String licensePlate;
    @Column(nullable = false, length = 100)
    private String ownerName;
    @Column(length = 20)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private VehicleType vehicleType;
    // Ngày bắt đầu và ngày hết hạn của vé
    private LocalDate startDate;
    private LocalDate endDate;
    // Số tiền khách đã trả cho vé (VNĐ)
    private long price;
    private boolean renewalCancelled;
    private LocalDateTime cancelledAt;
    private long refundAmount;
}
