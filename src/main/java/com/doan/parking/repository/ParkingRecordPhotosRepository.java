package com.doan.parking.repository;

import com.doan.parking.entity.ParkingRecordPhotos;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingRecordPhotosRepository extends JpaRepository<ParkingRecordPhotos, Long> {
}
