package com.doan.parking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "parking_record_photos")
@Getter
@Setter
public class ParkingRecordPhotos {
    @Id
    @Column(name = "parking_record_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "parking_record_id")
    private ParkingRecord parkingRecord;

    @Lob
    @Column(name = "front_photo", columnDefinition = "LONGBLOB")
    private byte[] frontPhoto;

    @Lob
    @Column(name = "plate_photo", columnDefinition = "LONGBLOB")
    private byte[] platePhoto;
}
