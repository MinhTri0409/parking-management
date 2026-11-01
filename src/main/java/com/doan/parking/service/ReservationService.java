package com.doan.parking.service;

import com.doan.parking.entity.AppUser;
import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import com.doan.parking.repository.PricingRepository;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationService {
    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING_PAYMENT, ReservationStatus.CONFIRMED);

    private final ParkingReservationRepository reservationRepository;
    private final ParkingRecordRepository recordRepository;
    private final PricingRepository pricingRepository;
    private final UserRepository userRepository;

    public record SlotOption(String code, boolean available) {}

    public long hourlyRate(VehicleType type) {
        return pricingRepository.findByVehicleType(type)
                .map(Pricing::getReservationHourlyPrice)
                .orElse(0L);
    }

    public List<SlotOption> availableSlots(VehicleType type, LocalDateTime start, LocalDateTime end) {
        validateTimeRange(start, end);
        Pricing pricing = pricingRepository.findByVehicleType(type)
                .orElseThrow(() -> new IllegalArgumentException("Chưa cấu hình loại xe này"));
        List<ParkingReservation> reservations =
                reservationRepository.findOverlapping(type, ACTIVE_STATUSES, start, end);
        List<ParkingRecord> records = recordRepository.findOverlappingVehicleType(type, start, end);
        Set<String> occupiedCodes = reservations.stream().map(ParkingReservation::getSlotCode)
                .collect(Collectors.toSet());
        occupiedCodes.addAll(records.stream().map(ParkingRecord::getSlotCode)
                .filter(code -> code != null).collect(Collectors.toSet()));

        List<SlotOption> slots = new ArrayList<>(pricing.getCapacity());
        for (int number = 1; number <= pricing.getCapacity(); number++) {
            String code = type.name() + "-" + String.format(Locale.ROOT, "%03d", number);
            slots.add(new SlotOption(code, !occupiedCodes.contains(code)));
        }
        return slots;
    }

    public long quote(VehicleType type, LocalDateTime start, LocalDateTime end) {
        validateTimeRange(start, end);
        Pricing pricing = pricingRepository.findByVehicleType(type)
                .orElseThrow(() -> new IllegalArgumentException("Chưa cấu hình loại xe này"));
        long hourlyRate = pricing.getReservationHourlyPrice() == null
                ? 0 : pricing.getReservationHourlyPrice();
        if (hourlyRate <= 0) {
            throw new IllegalArgumentException("Quản trị viên chưa cấu hình giá đặt chỗ theo giờ");
        }
        return Math.multiplyExact(calculateBillableHours(start, end), hourlyRate);
    }

    public long billableHours(LocalDateTime start, LocalDateTime end) {
        validateTimeRange(start, end);
        return calculateBillableHours(start, end);
    }

    private long calculateBillableHours(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        long fullHours = duration.toHours();
        return Math.max(1, fullHours + (duration.minusHours(fullHours).isZero() ? 0 : 1));
    }

    @Transactional
    public synchronized ParkingReservation create(String username, String plate, VehicleType type,
                                                   String slotCode, LocalDateTime start, LocalDateTime end) {
        validateTimeRange(start, end);
        String normalizedPlate = ParkingService.normalize(plate);
        if (!normalizedPlate.matches("[A-Z0-9][A-Z0-9 .-]{1,19}")) {
            throw new IllegalArgumentException("Biển số xe không hợp lệ");
        }
        if (recordRepository.findFirstByLicensePlateAndCheckOutIsNull(normalizedPlate).isPresent()) {
            throw new IllegalArgumentException("Xe đang ở trong bãi, không thể tạo đặt chỗ mới");
        }
        if (!reservationRepository.findOverlappingForPlate(normalizedPlate, ACTIVE_STATUSES, start, end).isEmpty()) {
            throw new IllegalArgumentException("Biển số này đã có đơn đặt chỗ trong khung giờ đã chọn");
        }

        Pricing pricing = pricingRepository.lockByVehicleType(type)
                .orElseThrow(() -> new IllegalArgumentException("Chưa cấu hình loại xe này"));
        if (pricing.getReservationHourlyPrice() == null || pricing.getReservationHourlyPrice() <= 0) {
            throw new IllegalArgumentException("Quản trị viên chưa cấu hình giá đặt chỗ theo giờ");
        }
        String normalizedSlot = slotCode == null ? "" : slotCode.trim().toUpperCase(Locale.ROOT);
        int slotNumber;
        try {
            slotNumber = Integer.parseInt(normalizedSlot.substring(normalizedSlot.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Vị trí đỗ không hợp lệ");
        }
        if (!normalizedSlot.matches(type.name() + "-[0-9]{3,}") || slotNumber > pricing.getCapacity()) {
            throw new IllegalArgumentException("Vị trí đỗ không hợp lệ");
        }

        boolean booked = reservationRepository.findOverlapping(type, ACTIVE_STATUSES, start, end).stream()
                .anyMatch(reservation -> reservation.getSlotCode().equals(normalizedSlot));
        boolean occupied = !recordRepository.findOverlappingSlot(type, normalizedSlot, start, end).isEmpty();
        if (booked || occupied) {
            throw new IllegalArgumentException("Vị trí này vừa được đặt hoặc không còn trống trong khung giờ đã chọn");
        }

        AppUser customer = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy tài khoản khách hàng"));
        long hours = calculateBillableHours(start, end);
        ParkingReservation reservation = new ParkingReservation();
        reservation.setBookingCode(newBookingCode());
        reservation.setCustomerUsername(customer.getUsername());
        reservation.setCustomerName(customer.getFullName() == null || customer.getFullName().isBlank()
                ? customer.getUsername() : customer.getFullName());
        reservation.setLicensePlate(normalizedPlate);
        reservation.setVehicleType(type);
        reservation.setSlotCode(normalizedSlot);
        reservation.setStartTime(start);
        reservation.setEndTime(end);
        reservation.setHourlyRate(pricing.getReservationHourlyPrice());
        reservation.setTotalAmount(Math.multiplyExact(hours, pricing.getReservationHourlyPrice()));
        reservation.setStatus(ReservationStatus.PENDING_PAYMENT);
        reservation.setCreatedAt(LocalDateTime.now());
        return reservationRepository.save(reservation);
    }

    @Transactional
    public void cancel(Long id, String username) {
        ParkingReservation reservation = reservationRepository.findByIdAndCustomerUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn đặt chỗ"));
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("Chỉ có thể hủy đơn chưa được xác nhận thanh toán");
        }
        if (!reservation.getStartTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Không thể hủy đơn sau khi khung giờ đặt chỗ đã bắt đầu");
        }
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
    }

    @Transactional
    public ParkingReservation confirmPayment(Long id) {
        ParkingReservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn đặt chỗ"));
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
            throw new IllegalArgumentException("Đơn đặt chỗ này không còn chờ xác nhận thanh toán");
        }
        if (!reservation.getStartTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Không thể xác nhận đặt chỗ sau khi khung giờ đã bắt đầu");
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setPaymentConfirmedAt(LocalDateTime.now());
        return reservationRepository.save(reservation);
    }

    @Transactional
    public void completeForCheckOut(String plate, String slotCode) {
        reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                plate, slotCode, ReservationStatus.ARRIVED).ifPresent(reservation -> {
                    reservation.setStatus(ReservationStatus.COMPLETED);
                    reservationRepository.save(reservation);
                });
    }

    public List<ParkingReservation> customerBookings(String username) {
        return reservationRepository.findByCustomerUsernameOrderByCreatedAtDesc(username);
    }

    public List<ParkingReservation> allBookings() {
        return reservationRepository.findAllByOrderByCreatedAtDesc();
    }

    public ParkingReservation customerBooking(String bookingCode, String username) {
        return reservationRepository.findByBookingCodeAndCustomerUsername(bookingCode, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn đặt chỗ"));
    }

    private String newBookingCode() {
        String code;
        do {
            code = "P-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                    .toUpperCase(Locale.ROOT);
        } while (reservationRepository.findByBookingCode(code).isPresent());
        return code;
    }

    private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("Thời gian trả xe phải sau thời gian nhận xe");
        }
        if (start.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Thời gian nhận xe không được ở trong quá khứ");
        }
    }
}
