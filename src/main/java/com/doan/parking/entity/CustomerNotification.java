package com.doan.parking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_notification", indexes = {
        @Index(name = "idx_customer_notification_pending", columnList = "recipient_username, read_at, created_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_customer_notification_reservation", columnNames = "reservation_id")
})
@Getter
@Setter
public class CustomerNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_username", nullable = false, length = 50)
    private String recipientUsername;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;
}
