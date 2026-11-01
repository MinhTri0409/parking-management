package com.doan.parking.repository;

import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.VehicleType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/** Truy vấn bảng giá. */
public interface PricingRepository extends JpaRepository<Pricing, Long> {
    // Lấy bảng giá theo loại xe
    Optional<Pricing> findByVehicleType(VehicleType vehicleType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pricing p where p.vehicleType = :vehicleType")
    Optional<Pricing> lockByVehicleType(@Param("vehicleType") VehicleType vehicleType);
}
