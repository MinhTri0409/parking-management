package com.doan.parking.repository;

import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.VehicleType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Truy vấn bảng lượt gửi xe. */
public interface ParkingRecordRepository extends JpaRepository<ParkingRecord, Long> {
    // Tìm lượt gửi chưa ra của một biển số (xe đang ở trong bãi)
    Optional<ParkingRecord> findFirstByLicensePlateAndCheckOutIsNull(String licensePlate);
    // Danh sách xe đang trong bãi, xe vào gần nhất xếp trước
    List<ParkingRecord> findByCheckOutIsNullOrderByCheckInDesc();
    Page<ParkingRecord> findByCheckOutIsNullOrderByCheckInDesc(Pageable pageable);
    // Danh sách xe đang trong bãi theo loại xe để dựng sơ đồ vị trí
    List<ParkingRecord> findByVehicleTypeAndCheckOutIsNullOrderByCheckInAsc(VehicleType vehicleType);
    @Query("select r from ParkingRecord r where r.vehicleType = :type "
            + "and r.checkIn < :endTime and (r.checkOut is null or r.checkOut > :startTime)")
    List<ParkingRecord> findOverlappingVehicleType(@Param("type") VehicleType type,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);
    // Đếm số xe đang trong bãi theo loại xe
    long countByVehicleTypeAndCheckOutIsNull(VehicleType vehicleType);

    @Query("select r from ParkingRecord r where r.vehicleType = :type and r.slotCode = :slot "
            + "and r.checkIn < :endTime and (r.checkOut is null or r.checkOut > :startTime)")
    List<ParkingRecord> findOverlappingSlot(@Param("type") VehicleType type,
                                             @Param("slot") String slot,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime);
    // Đếm số lượt xe vào từ một thời điểm trở đi
    long countByCheckInGreaterThanEqual(LocalDateTime time);
    // Lấy các lượt xe ra trong khoảng thời gian (dùng cho báo cáo)
    List<ParkingRecord> findByCheckOutGreaterThanEqualAndCheckOutLessThan(LocalDateTime from, LocalDateTime to);

    // Lọc theo nhân viên cho xe ra; khoảng ngày khớp giờ vào hoặc giờ ra để không bỏ sót lượt gửi qua đêm.
    @Query("select r from ParkingRecord r where lower(r.licensePlate) like lower(concat('%', :plate, '%')) "
         + "and (:vtype is null or r.vehicleType = :vtype) "
         + "and (:checkoutEmployee is null or r.checkedOutBy = :checkoutEmployee) "
         + "and ((r.checkIn >= :fromTime and r.checkIn < :toTime) "
         + "or (r.checkOut is not null and r.checkOut >= :fromTime and r.checkOut < :toTime)) "
         + "order by coalesce(r.checkOut, r.checkIn) desc, r.id desc")
    Page<ParkingRecord> search(@Param("plate") String plate, @Param("vtype") VehicleType vtype,
                               @Param("checkoutEmployee") String checkoutEmployee,
                               @Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime,
                               Pageable pageable);

    @Modifying
    @Query("update ParkingRecord r set r.createdBy = :newUsername where r.createdBy = :oldUsername")
    int renameCheckInAccount(@Param("oldUsername") String oldUsername,
                             @Param("newUsername") String newUsername);

    @Modifying
    @Query("update ParkingRecord r set r.checkedOutBy = :newUsername where r.checkedOutBy = :oldUsername")
    int renameCheckOutAccount(@Param("oldUsername") String oldUsername,
                              @Param("newUsername") String newUsername);
}
