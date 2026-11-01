package com.doan.parking.repository;

import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.entity.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ParkingReservationRepository extends JpaRepository<ParkingReservation, Long> {
    List<ParkingReservation> findByCustomerUsernameOrderByCreatedAtDesc(String customerUsername);

    List<ParkingReservation> findByCustomerUsernameAndStatusInAndPaymentConfirmedAtIsNotNull(
            String customerUsername, List<ReservationStatus> statuses);

    List<ParkingReservation> findAllByOrderByCreatedAtDesc();

    List<ParkingReservation> findByVehicleTypeAndStatusInAndEndTimeAfterOrderByStartTimeAsc(
            VehicleType vehicleType, List<ReservationStatus> statuses, LocalDateTime endTime);

    Optional<ParkingReservation> findByIdAndCustomerUsername(Long id, String customerUsername);

    Optional<ParkingReservation> findByBookingCode(String bookingCode);

    Optional<ParkingReservation> findByBookingCodeAndCustomerUsername(String bookingCode, String customerUsername);

    @Query("select r from ParkingReservation r where r.status in :statuses "
            + "and coalesce(r.paymentConfirmedAt, r.createdAt) >= :fromTime "
            + "and coalesce(r.paymentConfirmedAt, r.createdAt) < :toTime")
    List<ParkingReservation> findRevenueRecognized(@Param("statuses") List<ReservationStatus> statuses,
                                                    @Param("fromTime") LocalDateTime fromTime,
                                                    @Param("toTime") LocalDateTime toTime);

    @Query("select r from ParkingReservation r where r.vehicleType = :type "
            + "and r.status in :statuses and r.startTime < :endTime and r.endTime > :startTime")
    List<ParkingReservation> findOverlapping(@Param("type") VehicleType type,
                                              @Param("statuses") List<ReservationStatus> statuses,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);

    @Query("select r from ParkingReservation r where r.licensePlate = :plate "
            + "and r.status in :statuses and r.startTime < :endTime and r.endTime > :startTime")
    List<ParkingReservation> findOverlappingForPlate(@Param("plate") String plate,
                                                      @Param("statuses") List<ReservationStatus> statuses,
                                                      @Param("startTime") LocalDateTime startTime,
                                                      @Param("endTime") LocalDateTime endTime);

    @Query("select r from ParkingReservation r where r.licensePlate = :plate "
            + "and r.vehicleType = :type and r.status in :statuses "
            + "and r.startTime <= :time and r.endTime > :time")
    List<ParkingReservation> findActiveForPlate(@Param("plate") String plate,
                                                 @Param("type") VehicleType type,
                                                 @Param("statuses") List<ReservationStatus> statuses,
                                                 @Param("time") LocalDateTime time);

    @Query("select r from ParkingReservation r where r.vehicleType = :type and r.slotCode = :slot "
            + "and r.status in :statuses and r.startTime <= :time and r.endTime > :time")
    List<ParkingReservation> findActiveForSlot(@Param("type") VehicleType type,
                                                @Param("slot") String slot,
                                                @Param("statuses") List<ReservationStatus> statuses,
                                                @Param("time") LocalDateTime time);

    Optional<ParkingReservation> findFirstByLicensePlateAndSlotCodeAndStatus(
            String licensePlate, String slotCode, ReservationStatus status);
}
