package com.doan.parking.service;

import com.doan.parking.entity.AppUser;
import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import com.doan.parking.repository.PricingRepository;
import com.doan.parking.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    @Mock private ParkingReservationRepository reservationRepository;
    @Mock private ParkingRecordRepository recordRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private ReservationService reservationService;

    @Test
    void quoteUsesTheAdminConfiguredHourlyPrice() {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        when(pricingRepository.findByVehicleType(VehicleType.CAR))
                .thenReturn(Optional.of(pricing(25_000)));

        assertEquals(50_000, reservationService.quote(VehicleType.CAR, start, start.plusHours(2)));
    }

    @Test
    void quoteRoundsAnyStartedPartialHourUp() {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        when(pricingRepository.findByVehicleType(VehicleType.CAR))
                .thenReturn(Optional.of(pricing(25_000)));

        assertEquals(2, reservationService.billableHours(start, start.plusHours(1).plusSeconds(1)));
        assertEquals(50_000, reservationService.quote(
                VehicleType.CAR, start, start.plusHours(1).plusSeconds(1)));
    }

    @Test
    void createdReservationStoresTheSameConfiguredRateAndCalculatedTotal() {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        Pricing pricing = pricing(25_000);
        AppUser customer = new AppUser();
        customer.setUsername("customer");
        customer.setFullName("Khách hàng");

        when(recordRepository.findFirstByLicensePlateAndCheckOutIsNull("51A-12345"))
                .thenReturn(Optional.empty());
        when(reservationRepository.findOverlappingForPlate(
                "51A-12345",
                List.of(com.doan.parking.entity.ReservationStatus.PENDING_PAYMENT,
                        com.doan.parking.entity.ReservationStatus.CONFIRMED),
                start,
                start.plusHours(2))).thenReturn(List.of());
        when(pricingRepository.lockByVehicleType(VehicleType.CAR)).thenReturn(Optional.of(pricing));
        when(reservationRepository.findOverlapping(
                VehicleType.CAR,
                List.of(com.doan.parking.entity.ReservationStatus.PENDING_PAYMENT,
                        com.doan.parking.entity.ReservationStatus.CONFIRMED),
                start,
                start.plusHours(2))).thenReturn(List.of());
        when(recordRepository.findOverlappingSlot(VehicleType.CAR, "CAR-001", start, start.plusHours(2)))
                .thenReturn(List.of());
        when(userRepository.findByUsername("customer")).thenReturn(Optional.of(customer));
        when(reservationRepository.findByBookingCode(any())).thenReturn(Optional.empty());
        when(reservationRepository.save(any(ParkingReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ParkingReservation reservation = reservationService.create(
                "customer",
                "51A-12345",
                VehicleType.CAR,
                "CAR-001",
                start,
                start.plusHours(2));

        assertEquals(25_000, reservation.getHourlyRate());
        assertEquals(50_000, reservation.getTotalAmount());
    }

    private Pricing pricing(long hourlyRate) {
        Pricing pricing = new Pricing();
        pricing.setVehicleType(VehicleType.CAR);
        pricing.setCapacity(40);
        pricing.setReservationHourlyPrice(hourlyRate);
        return pricing;
    }
}
