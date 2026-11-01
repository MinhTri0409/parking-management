package com.doan.parking.service;

import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.MonthlyTicketRepository;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Tạo báo cáo doanh thu theo khoảng ngày. */
@Service
@RequiredArgsConstructor
public class ReportService {
    private static final List<ReservationStatus> PAID_RESERVATION_STATUSES =
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.ARRIVED, ReservationStatus.COMPLETED);

    private final ParkingRecordRepository recordRepo;
    private final ParkingReservationRepository reservationRepo;
    private final MonthlyTicketRepository ticketRepo;

    public record DayRow(LocalDate date, long count, long reservationCount, long revenue) {}

    public record Summary(List<DayRow> days, Map<String, Long> byType,
                          long turnRevenue, long reservationRevenue, long ticketRevenue,
                          long vehicles, long reservations) {
        public long total() {
            return turnRevenue + reservationRevenue + ticketRevenue;
        }
    }

    private static final class DailyRevenue {
        private long vehicleCount;
        private long reservationCount;
        private long revenue;
    }

    // Doanh thu được ghi nhận khi xe trả phí, vé tháng đăng ký, hoặc thanh toán đặt chỗ được xác nhận.
    public Summary build(LocalDate from, LocalDate to) {
        List<ParkingRecord> records = recordRepo.findByCheckOutGreaterThanEqualAndCheckOutLessThan(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<ParkingReservation> reservations = reservationRepo.findRevenueRecognized(
                PAID_RESERVATION_STATUSES, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        var tickets = ticketRepo.findByStartDateBetween(from, to);

        Map<LocalDate, DailyRevenue> daily = new TreeMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (VehicleType type : VehicleType.values()) {
            byType.put(type.getLabel(), 0L);
        }

        long turnRevenue = 0;
        for (ParkingRecord record : records) {
            long fee = record.getFee();
            turnRevenue += fee;
            byType.merge(record.getVehicleType().getLabel(), fee, Long::sum);
            DailyRevenue row = daily.computeIfAbsent(record.getCheckOut().toLocalDate(), ignored -> new DailyRevenue());
            row.vehicleCount++;
            row.revenue += fee;
        }

        long reservationRevenue = 0;
        for (ParkingReservation reservation : reservations) {
            long amount = reservation.getTotalAmount();
            reservationRevenue += amount;
            byType.merge(reservation.getVehicleType().getLabel(), amount, Long::sum);
            LocalDate paidDate = (reservation.getPaymentConfirmedAt() == null
                    ? reservation.getCreatedAt() : reservation.getPaymentConfirmedAt()).toLocalDate();
            DailyRevenue row = daily.computeIfAbsent(paidDate, ignored -> new DailyRevenue());
            row.reservationCount++;
            row.revenue += amount;
        }

        long ticketRevenue = 0;
        for (var ticket : tickets) {
            long price = ticket.getPrice();
            ticketRevenue += price;
            byType.merge(ticket.getVehicleType().getLabel(), price, Long::sum);
            daily.computeIfAbsent(ticket.getStartDate(), ignored -> new DailyRevenue()).revenue += price;
        }

        List<DayRow> days = daily.entrySet().stream()
                .map(entry -> new DayRow(entry.getKey(), entry.getValue().vehicleCount,
                        entry.getValue().reservationCount, entry.getValue().revenue))
                .collect(Collectors.toCollection(ArrayList::new));
        return new Summary(days, byType, turnRevenue, reservationRevenue, ticketRevenue,
                records.size(), reservations.size());
    }

    public String toCsv(Summary summary) {
        StringBuilder csv = new StringBuilder("Ngay,So luot xe,So don dat cho,Doanh thu (VND)\n");
        for (DayRow day : summary.days()) {
            csv.append(day.date()).append(',')
                    .append(day.count()).append(',')
                    .append(day.reservationCount()).append(',')
                    .append(day.revenue()).append('\n');
        }
        csv.append("Tong tien gui luot,,,").append(summary.turnRevenue()).append('\n');
        csv.append("Tong tien dat cho online,,,").append(summary.reservationRevenue()).append('\n');
        csv.append("Tong tien ve thang,,,").append(summary.ticketRevenue()).append('\n');
        csv.append("TONG CONG,,,").append(summary.total()).append('\n');
        return csv.toString();
    }
}
