package com.doan.parking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "contact_inquiry")
@Getter
@Setter
public class ContactInquiry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "customer_type", nullable = false, length = 20)
    private String customerType;

    @Column(nullable = false, length = 5000)
    private String message;

    @Column(name = "email_status", nullable = false, length = 20)
    private String emailStatus;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;
}
