package com.doan.parking.service;

import com.doan.parking.entity.ParkingRecord;
import com.doan.parking.entity.ParkingRecordPhotos;
import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.MonthlyTicketRepository;
import com.doan.parking.repository.ParkingRecordPhotosRepository;
import com.doan.parking.repository.ParkingRecordRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import com.doan.parking.repository.PricingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParkingServiceCheckInTest {
    @Mock private ParkingRecordRepository recordRepository;
    @Mock private ParkingRecordPhotosRepository recordPhotosRepository;
    @Mock private ParkingReservationRepository reservationRepository;
    @Mock private PricingRepository pricingRepository;
    @Mock private MonthlyTicketRepository ticketRepository;
    @InjectMocks private ParkingService parkingService;

    @Test
    void checkInStoresBothCapturedPhotosWithTheNewParkingRecord() {
        Pricing pricing = new Pricing();
        pricing.setCapacity(10);
        when(recordRepository.findFirstByLicensePlateAndCheckOutIsNull("51A-12345"))
                .thenReturn(Optional.empty());
        when(pricingRepository.lockByVehicleType(VehicleType.CAR)).thenReturn(Optional.of(pricing));
        when(recordRepository.findByVehicleTypeAndCheckOutIsNullOrderByCheckInAsc(VehicleType.CAR))
                .thenReturn(List.of());
        when(reservationRepository.findOverlapping(any(), anyList(), any(), any())).thenReturn(List.of());
        when(ticketRepository
                .findFirstByLicensePlateIgnoreCaseAndVehicleTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCancelledAtIsNull(
                        any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(recordRepository.save(any(ParkingRecord.class))).thenAnswer(invocation -> {
            ParkingRecord record = invocation.getArgument(0);
            record.setId(42L);
            return record;
        });

        byte[] frontPhoto = {1, 2, 3};
        byte[] platePhoto = {4, 5, 6};
        ParkingRecord record = parkingService.checkIn(
                "51A-12345", VehicleType.CAR, "staff", null, frontPhoto, platePhoto);

        ArgumentCaptor<ParkingRecordPhotos> photosCaptor = ArgumentCaptor.forClass(ParkingRecordPhotos.class);
        verify(recordPhotosRepository).save(photosCaptor.capture());
        ParkingRecordPhotos photos = photosCaptor.getValue();
        assertEquals(42L, record.getId());
        assertEquals(record, photos.getParkingRecord());
        assertArrayEquals(frontPhoto, photos.getFrontPhoto());
        assertArrayEquals(platePhoto, photos.getPlatePhoto());
    }
}
