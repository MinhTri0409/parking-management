package com.doan.parking.service;

import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.ParkingRecordPhotos;
import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.PaymentMethod;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.MonthlyTicketRepository;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.ParkingRecordPhotosRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import com.doan.parking.repository.PricingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Xử lý nghiệp vụ gửi xe: cho xe vào, cho xe ra, tính tiền, tìm kiếm, thống kê. */
@Service
@RequiredArgsConstructor
public class ParkingService {
    private static final List<ReservationStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(ReservationStatus.PENDING_PAYMENT, ReservationStatus.CONFIRMED);

    private final ParkingRecordRepository recordRepo;
    private final ParkingRecordPhotosRepository recordPhotosRepo;
    private final ParkingReservationRepository reservationRepo;
    private final PricingRepository pricingRepo;
    private final MonthlyTicketRepository ticketRepo;

    // Thống kê sức chứa của một loại xe: đã dùng / tối đa
    public record TypeStat(String label, long used, int capacity) {
        public int percent() { return capacity == 0 ? 0 : (int) Math.min(100, used * 100 / capacity); }
    }

    public record ParkingSlot(String code, VehicleType type, ParkingRecord record,
                              ParkingReservation reservation) {
        public boolean occupied() { return record != null; }
        public boolean reserved() { return !occupied() && reservation != null; }
    }

    public record CheckoutQuote(ParkingRecord record, long amount, boolean prepaidReservation,
                                String bookingCode, LocalDateTime reservedUntil,
                                long extraHours, long hourlyRate) {}

    public record ParkingSection(TypeStat stats, VehicleType type, List<ParkingSlot> slots,
                                 List<ParkingRecord> overflowRecords) {}

    // Chuẩn hóa biển số: bỏ khoảng trắng thừa và viết hoa
    public static String normalize(String plate) {
        return plate == null ? "" : plate.trim().toUpperCase(Locale.ROOT);
    }

    @Transactional
    // Cho xe vào bãi: kiểm tra hợp lệ rồi lưu lượt gửi mới
    public ParkingRecord checkIn(String plate, VehicleType type, String staff) {
        return checkIn(plate, type, staff, null);
    }

    @Transactional
    public ParkingRecord checkIn(String plate, VehicleType type, String staff, String requestedSlot) {
        return checkIn(plate, type, staff, requestedSlot, null, null);
    }

    @Transactional
    public ParkingRecord checkIn(String plate, VehicleType type, String staff, String requestedSlot,
                                 byte[] frontPhoto, byte[] platePhoto) {
        String normalizedPlate = normalize(plate);
        plate = normalizedPlate;
        // 1) Biển số không được để trống
        if (plate.isEmpty()) throw new IllegalArgumentException("Biển số không được để trống");
        // 2) Xe đang trong bãi thì không cho vào lần nữa
        if (recordRepo.findFirstByLicensePlateAndCheckOutIsNull(plate).isPresent())
            throw new IllegalArgumentException("Xe " + plate + " đang ở trong bãi");
        // 3) Phải có bảng giá cho loại xe này
        Pricing p = pricingRepo.lockByVehicleType(type)
                .orElseThrow(() -> new IllegalArgumentException("Chưa cấu hình giá cho loại xe này"));
        List<ParkingRecord> activeRecords = recordRepo.findByVehicleTypeAndCheckOutIsNullOrderByCheckInAsc(type);
        LocalDateTime now = LocalDateTime.now();
        List<ParkingReservation> activeReservations = reservationRepo.findOverlapping(type,
                List.of(ReservationStatus.PENDING_PAYMENT, ReservationStatus.CONFIRMED),
                now.minusNanos(1), now.plusNanos(1));
        ParkingReservation ownReservation = activeReservations.stream()
                .filter(reservation -> reservation.getLicensePlate().equals(normalizedPlate))
                .findFirst().orElse(null);
        if (ownReservation != null && ownReservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new IllegalArgumentException("Đơn đặt chỗ chưa được nhân viên xác nhận thanh toán");
        }
        if (ownReservation != null && requestedSlot != null && !requestedSlot.isBlank()
                && !ownReservation.getSlotCode().equalsIgnoreCase(requestedSlot.trim())) {
            throw new IllegalArgumentException("Xe đã có đặt chỗ tại vị trí " + ownReservation.getSlotCode());
        }
        // 4) Bãi đã đầy thì không nhận thêm
        if (activeRecords.size() >= p.getCapacity())
            throw new IllegalArgumentException("Bãi " + type.getLabel() + " đã đầy");
        String slotCode = ownReservation != null ? ownReservation.getSlotCode()
                : requestedSlot == null || requestedSlot.isBlank()
                ? findFirstAvailableSlot(type, p.getCapacity(), activeRecords, activeReservations)
                : validateRequestedSlot(type, p.getCapacity(), requestedSlot, activeRecords);
        if (ownReservation == null && activeReservations.stream()
                .anyMatch(reservation -> reservation.getSlotCode().equalsIgnoreCase(slotCode))) {
            throw new IllegalArgumentException("Vị trí " + slotCode + " đang được khách hàng đặt trước");
        }
        if (ownReservation != null) {
            ownReservation.setStatus(ReservationStatus.ARRIVED);
            reservationRepo.save(ownReservation);
        }

        LocalDate today = LocalDate.now();
        ParkingRecord r = new ParkingRecord();
        r.setLicensePlate(plate);
        r.setSlotCode(slotCode);
        r.setVehicleType(type);
        r.setCheckIn(LocalDateTime.now());
        r.setCreatedBy(staff);
        // Chỉ miễn phí khi vé còn hạn khớp cả biển số và loại xe.
        r.setMonthlyTicket(ticketRepo
                .findFirstByLicensePlateIgnoreCaseAndVehicleTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCancelledAtIsNull(
                        plate, type, today, today)
                .isPresent());
        ParkingRecord saved = recordRepo.save(r);
        if (frontPhoto != null || platePhoto != null) {
            ParkingRecordPhotos photos = new ParkingRecordPhotos();
            photos.setParkingRecord(saved);
            photos.setFrontPhoto(frontPhoto);
            photos.setPlatePhoto(platePhoto);
            recordPhotosRepo.save(photos);
        }
        return saved;
    }

    @Transactional
    // Cho xe ra bãi: ghi giờ ra và tính tiền
    public ParkingRecord checkOut(@NonNull Long id, String staff, PaymentMethod paymentMethod) {
        ParkingRecord r = recordRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt gửi xe"));
        // Xe đã ra rồi thì không xử lý lại
        if (r.getCheckOut() != null) throw new IllegalArgumentException("Xe này đã ra khỏi bãi");
        LocalDateTime now = LocalDateTime.now();
        boolean monthlyTicket = r.isMonthlyTicket();
        CheckoutQuote quote = checkoutQuote(r, now);
        if (quote.prepaidReservation() && quote.amount() == 0) {
            paymentMethod = null;
        } else if (quote.prepaidReservation()) {
            if (paymentMethod == null || paymentMethod == PaymentMethod.MONTHLY_TICKET) {
                throw new IllegalArgumentException("Vui lòng chọn phương thức thanh toán hợp lệ");
            }
        } else if (monthlyTicket) {
            paymentMethod = PaymentMethod.MONTHLY_TICKET;
        } else if (paymentMethod == null || paymentMethod == PaymentMethod.MONTHLY_TICKET) {
            throw new IllegalArgumentException("Vui lòng chọn phương thức thanh toán hợp lệ");
        }
        r.setCheckOut(now);
        r.setCheckedOutBy(staff);
        r.setPaymentMethod(paymentMethod);
        r.setFee(monthlyTicket && !quote.prepaidReservation() ? 0 : quote.amount());
        reservationRepo.findFirstByLicensePlateAndSlotCodeAndStatus(
                r.getLicensePlate(), r.getSlotCode(), ReservationStatus.ARRIVED).ifPresent(reservation -> {
                    reservation.setStatus(ReservationStatus.COMPLETED);
                    reservationRepo.save(reservation);
                });
        return recordRepo.save(r);
    }

    @Transactional
    public int checkOutOverflowMonthlyTickets(String staff) {
        List<ParkingRecord> eligibleRecords = parkingMap().stream()
                .flatMap(section -> section.overflowRecords().stream())
                .filter(ParkingRecord::isMonthlyTicket)
                .toList();
        for (ParkingRecord record : eligibleRecords) {
            checkOut(record.getId(), staff, PaymentMethod.MONTHLY_TICKET);
        }
        return eligibleRecords.size();
    }

    public CheckoutQuote checkoutQuote(@NonNull Long id) {
        ParkingRecord record = recordRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy lượt gửi xe"));
        if (record.getCheckOut() != null) throw new IllegalArgumentException("Xe này đã ra khỏi bãi");
        return checkoutQuote(record, LocalDateTime.now());
    }

    private CheckoutQuote checkoutQuote(ParkingRecord record, LocalDateTime checkoutTime) {
        ParkingReservation reservation = reservationRepo.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED).orElse(null);
        boolean prepaidReservation = reservation != null && reservation.getPaymentConfirmedAt() != null;
        if (prepaidReservation) {
            Duration late = Duration.between(reservation.getEndTime(), checkoutTime);
            long lateSeconds = late.isNegative() || late.isZero()
                    ? 0
                    : late.getSeconds() + (late.getNano() == 0 ? 0 : 1);
            long extraHours = (lateSeconds + 3_599) / 3_600;
            long amount = Math.multiplyExact(extraHours, reservation.getHourlyRate());
            return new CheckoutQuote(record, amount, true, reservation.getBookingCode(),
                    reservation.getEndTime(), extraHours, reservation.getHourlyRate());
        }

        if (record.isMonthlyTicket()) {
            return new CheckoutQuote(record, 0, false, null, null, 0, 0);
        }
        return new CheckoutQuote(record, calculateFee(record, checkoutTime), false, null, null, 0, 0);
    }

    /** Phí = giá lượt x số ngày lịch (gửi qua đêm tính thêm ngày). */
    public long calculateFee(ParkingRecord r, LocalDateTime out) {
        Pricing p = pricingRepo.findByVehicleType(r.getVehicleType()).orElseThrow();
        // Số ngày lịch gửi xe (cùng ngày = 1 ngày, qua đêm tính thêm ngày)
        long days = ChronoUnit.DAYS.between(r.getCheckIn().toLocalDate(), out.toLocalDate()) + 1;
        return days * p.getTurnPrice();
    }

    // Danh sách xe đang trong bãi
    public List<ParkingRecord> inLot() {
        return recordRepo.findByCheckOutIsNullOrderByCheckInDesc();
    }

    public Page<ParkingRecord> inLot(int page) {
        int pageNumber = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageNumber, 10);
        Page<ParkingRecord> results = recordRepo.findByCheckOutIsNullOrderByCheckInDesc(pageable);
        if (results.getTotalPages() > 0 && pageNumber >= results.getTotalPages()) {
            return recordRepo.findByCheckOutIsNullOrderByCheckInDesc(
                    PageRequest.of(results.getTotalPages() - 1, 10));
        }
        return results;
    }

    // Tìm kiếm lịch sử gửi xe có phân trang (10 dòng/trang); điều kiện nào trống thì không lọc
    public Page<ParkingRecord> search(String plate, VehicleType type, String checkoutEmployee,
                                      LocalDate from, LocalDate to, int page) {
        LocalDateTime f = from == null ? LocalDateTime.of(2000, 1, 1, 0, 0) : from.atStartOfDay();
        LocalDateTime t = to == null ? LocalDateTime.of(2999, 1, 1, 0, 0) : to.plusDays(1).atStartOfDay();
        int requestedPage = Math.max(page, 0);
        Page<ParkingRecord> results = recordRepo.search(plate == null ? "" : plate.trim(), type,
                checkoutEmployee, f, t, PageRequest.of(requestedPage, 10));
        if (results.getTotalPages() == 0) {
            return requestedPage == 0
                    ? results
                    : recordRepo.search(plate == null ? "" : plate.trim(), type, checkoutEmployee,
                            f, t, PageRequest.of(0, 10));
        }
        if (requestedPage >= results.getTotalPages()) {
            return recordRepo.search(plate == null ? "" : plate.trim(), type, checkoutEmployee,
                    f, t, PageRequest.of(results.getTotalPages() - 1, 10));
        }
        return results;
    }

    // Thống kê số xe hiện có / sức chứa của từng loại xe
    public List<TypeStat> occupancy() {
        List<TypeStat> list = new ArrayList<>();
        for (VehicleType t : VehicleType.values()) {
            int cap = capacityOf(t);
            list.add(new TypeStat(t.getLabel(), recordRepo.countByVehicleTypeAndCheckOutIsNull(t), cap));
        }
        return list;
    }

    /** Lấy sơ đồ bãi từ sức chứa và các lượt gửi đang hoạt động trong database. */
    @Transactional
    public List<ParkingSection> parkingMap() {
        List<ParkingSection> sections = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (VehicleType type : VehicleType.values()) {
            int capacity = capacityOf(type);
            List<ParkingRecord> activeRecords =
                    recordRepo.findByVehicleTypeAndCheckOutIsNullOrderByCheckInAsc(type);
            List<ParkingReservation> activeReservations =
                    reservationRepo.findByVehicleTypeAndStatusInAndEndTimeAfterOrderByStartTimeAsc(
                            type, ACTIVE_RESERVATION_STATUSES, now);
            Map<Integer, ParkingRecord> recordsBySlot = new HashMap<>();
            Map<Integer, ParkingReservation> reservationsBySlot = new HashMap<>();
            Set<Integer> usedSlots = new HashSet<>();
            List<ParkingRecord> unassignedRecords = new ArrayList<>();

            for (ParkingRecord record : activeRecords) {
                Integer number = parseSlotNumber(type, record.getSlotCode());
                if (number != null && number <= capacity && usedSlots.add(number)) {
                    String slotCode = formatSlotCode(type, number);
                    if (!slotCode.equals(record.getSlotCode())) {
                        record.setSlotCode(slotCode);
                        recordRepo.save(record);
                    }
                    recordsBySlot.put(number, record);
                } else {
                    unassignedRecords.add(record);
                }
            }

            for (ParkingReservation reservation : activeReservations) {
                Integer number = parseSlotNumber(type, reservation.getSlotCode());
                if (number != null && number <= capacity && !usedSlots.contains(number)) {
                    reservationsBySlot.putIfAbsent(number, reservation);
                }
            }

            Set<Integer> unavailableSlots = new HashSet<>(usedSlots);
            unavailableSlots.addAll(reservationsBySlot.keySet());
            List<ParkingRecord> overflowRecords = new ArrayList<>();
            for (ParkingRecord record : unassignedRecords) {
                Integer number = findFirstFreeSlotNumber(capacity, unavailableSlots);
                if (number == null) {
                    if (record.getSlotCode() != null) {
                        record.setSlotCode(null);
                        recordRepo.save(record);
                    }
                    overflowRecords.add(record);
                    continue;
                }
                String slotCode = formatSlotCode(type, number);
                if (!slotCode.equals(record.getSlotCode())) {
                    record.setSlotCode(slotCode);
                    recordRepo.save(record);
                }
                usedSlots.add(number);
                unavailableSlots.add(number);
                recordsBySlot.put(number, record);
            }

            List<ParkingSlot> slots = new ArrayList<>();
            for (int number = 1; number <= capacity; number++) {
                slots.add(new ParkingSlot(formatSlotCode(type, number), type, recordsBySlot.get(number),
                        reservationsBySlot.get(number)));
            }
            long used = activeRecords.size();
            sections.add(new ParkingSection(new TypeStat(type.getLabel(), used, capacity), type, slots,
                    overflowRecords));
        }
        return sections;
    }

    private String findFirstAvailableSlot(VehicleType type, int capacity, List<ParkingRecord> activeRecords,
                                          List<ParkingReservation> activeReservations) {
        Set<Integer> occupied = new HashSet<>();
        for (ParkingRecord record : activeRecords) {
            Integer number = parseSlotNumber(type, record.getSlotCode());
            if (number != null) occupied.add(number);
        }
        for (ParkingReservation reservation : activeReservations) {
            Integer number = parseSlotNumber(type, reservation.getSlotCode());
            if (number != null) occupied.add(number);
        }
        Integer number = findFirstFreeSlotNumber(capacity, occupied);
        if (number == null) throw new IllegalArgumentException("Bãi " + type.getLabel() + " đã đầy");
        return formatSlotCode(type, number);
    }

    private String validateRequestedSlot(VehicleType type, int capacity, String requestedSlot,
                                         List<ParkingRecord> activeRecords) {
        Integer number = parseSlotNumber(type, requestedSlot.trim().toUpperCase(Locale.ROOT));
        if (number == null || number > capacity) {
            throw new IllegalArgumentException("Vị trí đỗ không hợp lệ");
        }
        boolean occupied = activeRecords.stream()
                .anyMatch(record -> number.equals(parseSlotNumber(type, record.getSlotCode())));
        if (occupied) throw new IllegalArgumentException("Vị trí " + requestedSlot + " đã có xe");
        return formatSlotCode(type, number);
    }

    private Integer findFirstFreeSlotNumber(int capacity, Set<Integer> occupied) {
        for (int number = 1; number <= capacity; number++) {
            if (!occupied.contains(number)) return number;
        }
        return null;
    }

    private int capacityOf(VehicleType type) {
        var pricing = pricingRepo.findByVehicleType(type);
        return pricing.isPresent() ? pricing.get().getCapacity() : 0;
    }

    private Integer parseSlotNumber(VehicleType type, String slotCode) {
        if (slotCode == null) return null;
        String prefix = slotPrefix(type) + "-";
        String[] acceptedPrefixes = type == VehicleType.MOTORELECTRIC
                ? new String[]{prefix, "ELECTRICMOTORCYCLE-", "BICYCLE-"}
                : new String[]{prefix};
        String matchedPrefix = null;
        for (String acceptedPrefix : acceptedPrefixes) {
            if (slotCode.startsWith(acceptedPrefix)) {
                matchedPrefix = acceptedPrefix;
                break;
            }
        }
        if (matchedPrefix == null) return null;
        try {
            int number = Integer.parseInt(slotCode.substring(matchedPrefix.length()));
            return number > 0 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatSlotCode(VehicleType type, int number) {
        return slotPrefix(type) + "-" + String.format(Locale.ROOT, "%03d", number);
    }

    private String slotPrefix(VehicleType type) {
        return type.name();
    }

    // Số lượt xe vào trong hôm nay
    public long todayCheckIns() {
        return recordRepo.countByCheckInGreaterThanEqual(LocalDate.now().atStartOfDay());
    }

    // Doanh thu hôm nay = tổng phí của các xe ra trong ngày
    public long todayRevenue() {
        LocalDateTime s = LocalDate.now().atStartOfDay();
        long revenue = 0;
        for (ParkingRecord record : recordRepo.findByCheckOutGreaterThanEqualAndCheckOutLessThan(s, s.plusDays(1))) {
            revenue += record.getFee();
        }
        return revenue;
    }
}
