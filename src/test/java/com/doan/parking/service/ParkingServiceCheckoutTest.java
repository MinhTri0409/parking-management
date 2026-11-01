package com.doan.parking.service;

import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.PaymentMethod;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.MonthlyTicketRepository;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import com.doan.parking.repository.PricingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParkingServiceCheckoutTest {
    @Mock private ParkingRecordRepository recordRepository;
    @Mock private ParkingReservationRepository reservationRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private MonthlyTicketRepository ticketRepository;
    @InjectMocks private ParkingService parkingService;

    @Test
    void prepaidReservationReturnedBeforeEndHasNoAdditionalFee() {
        ParkingRecord record = parkingRecord();
        ParkingReservation reservation = arrivedReservation(LocalDateTime.now().plusMinutes(30));
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.of(reservation));

        ParkingService.CheckoutQuote quote = parkingService.checkoutQuote(1L);

        assertTrue(quote.prepaidReservation());
        assertEquals(0, quote.amount());
        assertEquals(0, quote.extraHours());
        assertEquals(reservation.getBookingCode(), quote.bookingCode());
    }

    @Test
    void prepaidReservationReturnedLateChargesEveryStartedHour() {
        ParkingRecord record = parkingRecord();
        ParkingReservation reservation = arrivedReservation(LocalDateTime.now().minusMinutes(61));
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.of(reservation));

        ParkingService.CheckoutQuote quote = parkingService.checkoutQuote(1L);

        assertTrue(quote.prepaidReservation());
        assertEquals(2, quote.extraHours());
        assertEquals(10_000, quote.amount());
        assertEquals(5_000, quote.hourlyRate());
    }

    @Test
    void ordinaryParkingStillUsesTheConfiguredDailyRate() {
        ParkingRecord record = parkingRecord();
        record.setCheckIn(LocalDateTime.now().minusDays(1));
        Pricing pricing = new Pricing();
        pricing.setTurnPrice(20_000);
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.empty());
        when(pricingRepository.findByVehicleType(VehicleType.CAR)).thenReturn(Optional.of(pricing));

        ParkingService.CheckoutQuote quote = parkingService.checkoutQuote(1L);

        assertFalse(quote.prepaidReservation());
        assertTrue(quote.amount() >= 20_000);
    }

    @Test
    void prepaidReservationCanCheckoutOnTimeWithoutPaymentMethod() {
        ParkingRecord record = parkingRecord();
        ParkingReservation reservation = arrivedReservation(LocalDateTime.now().plusMinutes(30));
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.of(reservation));
        when(recordRepository.save(record)).thenReturn(record);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        ParkingRecord checkedOut = parkingService.checkOut(1L, "staff", null);

        assertEquals(0, checkedOut.getFee());
        assertNull(checkedOut.getPaymentMethod());
        assertEquals(ReservationStatus.COMPLETED, reservation.getStatus());
        verify(recordRepository).save(record);
        verify(reservationRepository).save(reservation);
    }

    @Test
    void latePrepaidReservationRequiresPaymentMethod() {
        ParkingRecord record = parkingRecord();
        ParkingReservation reservation = arrivedReservation(LocalDateTime.now().minusMinutes(61));
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.of(reservation));

        assertThrows(IllegalArgumentException.class, () -> parkingService.checkOut(1L, "staff", null));
    }

    @Test
    void latePrepaidReservationChargesOnlyTheAdditionalHours() {
        ParkingRecord record = parkingRecord();
        record.setMonthlyTicket(true);
        ParkingReservation reservation = arrivedReservation(LocalDateTime.now().minusMinutes(61));
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.of(reservation));
        when(recordRepository.save(record)).thenReturn(record);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        ParkingRecord checkedOut = parkingService.checkOut(1L, "staff", PaymentMethod.QR);

        assertEquals(10_000, checkedOut.getFee());
        assertEquals(PaymentMethod.QR, checkedOut.getPaymentMethod());
        assertEquals(ReservationStatus.COMPLETED, reservation.getStatus());
    }

    @Test
    void alreadyPaidMethodChecksOutWithoutChargingAgain() {
        ParkingRecord record = parkingRecord();
        Pricing pricing = new Pricing();
        pricing.setTurnPrice(20_000);
        when(recordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(reservationRepository.findFirstByLicensePlateAndSlotCodeAndStatus(
                record.getLicensePlate(), record.getSlotCode(), ReservationStatus.ARRIVED))
                .thenReturn(Optional.empty());
        when(pricingRepository.findByVehicleType(VehicleType.CAR)).thenReturn(Optional.of(pricing));
        when(recordRepository.save(record)).thenReturn(record);

        ParkingRecord checkedOut = parkingService.checkOut(1L, "staff", PaymentMethod.ALREADY_PAID);

        assertEquals(PaymentMethod.ALREADY_PAID, checkedOut.getPaymentMethod());
        assertTrue(checkedOut.getFee() > 0);
    }

    private ParkingRecord parkingRecord() {
        ParkingRecord record = new ParkingRecord();
        record.setId(1L);
        record.setLicensePlate("51A-12345");
        record.setSlotCode("CAR-001");
        record.setVehicleType(VehicleType.CAR);
        record.setCheckIn(LocalDateTime.now().minusHours(2));
        return record;
    }

    private ParkingReservation arrivedReservation(LocalDateTime endTime) {
        ParkingReservation reservation = new ParkingReservation();
        reservation.setId(2L);
        reservation.setBookingCode("P-BOOKING1234");
        reservation.setLicensePlate("51A-12345");
        reservation.setSlotCode("CAR-001");
        reservation.setStatus(ReservationStatus.ARRIVED);
        reservation.setEndTime(endTime);
        reservation.setHourlyRate(5_000);
        reservation.setPaymentConfirmedAt(LocalDateTime.now().minusHours(3));
        return reservation;
    }
}
