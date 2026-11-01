package com.doan.parking.repository;

import com.doan.parking.entity.CustomerNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, Long> {
    boolean existsByReservationId(Long reservationId);

    List<CustomerNotification> findAllByRecipientUsernameAndReadAtIsNullOrderByCreatedAtAsc(
            String recipientUsername);

    Optional<CustomerNotification> findByIdAndRecipientUsernameAndReadAtIsNull(
            Long id, String recipientUsername);
}
