package com.doan.parking.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Bảng giá và sức chứa cho từng loại xe. */
@Entity
@Getter @Setter
public class Pricing {
    // Khóa chính, tự tăng
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // Loại xe áp dụng giá (mỗi loại chỉ có một dòng giá)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 20, columnDefinition = "varchar(20)")
    private VehicleType vehicleType;
    private long turnPrice;   // giá mỗi lượt/ngày (VNĐ)
    private long monthPrice;  // giá vé tháng (VNĐ)
    private int capacity;     // sức chứa
    private Long reservationHourlyPrice;
}
