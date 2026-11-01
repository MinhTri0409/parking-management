package com.doan.parking.repository;

import com.doan.parking.entity.MonthlyTicket;
import com.doan.parking.entity.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Truy vấn bảng vé tháng. */
public interface MonthlyTicketRepository extends JpaRepository<MonthlyTicket, Long> {
    // Tìm vé tháng còn hiệu lực của một biển số và loại xe tại ngày cho trước
    Optional<MonthlyTicket> findFirstByLicensePlateIgnoreCaseAndVehicleTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCancelledAtIsNull(
            String plate, VehicleType vehicleType, LocalDate d1, LocalDate d2);
    boolean existsByLicensePlateIgnoreCaseAndVehicleTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCancelledAtIsNull(
            String plate, VehicleType vehicleType, LocalDate latestStart, LocalDate earliestEnd);
    // Tìm vé tháng theo biển số (không phân biệt hoa thường)
    List<MonthlyTicket> findByLicensePlateContainingIgnoreCaseOrderByEndDateDesc(String q);
    // Lấy các vé đăng ký trong khoảng ngày (dùng cho báo cáo doanh thu)
    List<MonthlyTicket> findByStartDateBetween(LocalDate from, LocalDate to);
}
