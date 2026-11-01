package com.doan.parking.service;

import com.doan.parking.entity.CustomerNotification;
import com.doan.parking.entity.ParkingReservation;
import com.doan.parking.entity.ReservationStatus;
import com.doan.parking.repository.CustomerNotificationRepository;
import com.doan.parking.repository.ParkingReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
public class CustomerNotificationService {
    private static final long STREAM_TIMEOUT_MILLIS = 0L;

    private final CustomerNotificationRepository notificationRepository;
    private final ParkingReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, List<SseEmitter>> emittersByUsername = new ConcurrentHashMap<>();

    @Transactional
    public void notifyCustomer(String username, Long reservationId, String message) {
        if (notificationRepository.existsByReservationId(reservationId)) {
            return;
        }
        CustomerNotification notification = new CustomerNotification();
        notification.setRecipientUsername(username);
        notification.setReservationId(reservationId);
        notification.setMessage(message);
        notification.setCreatedAt(LocalDateTime.now());
        CustomerNotification savedNotification = notificationRepository.saveAndFlush(notification);
        eventPublisher.publishEvent(new NotificationCreated(
                username, savedNotification.getId(), savedNotification.getMessage()));
    }

    public SseEmitter subscribe(String username) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        List<SseEmitter> emitters = emittersByUsername.computeIfAbsent(
                username, ignored -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(username, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            removeEmitter(username, emitter);
        });
        emitter.onError(error -> removeEmitter(username, emitter));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(NotificationCreated notification) {
        List<SseEmitter> emitters = emittersByUsername.get(notification.username());
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("customer-notification")
                        .id(notification.id().toString())
                        .data(Map.of("id", notification.id(), "message", notification.message())));
            } catch (IOException | IllegalStateException exception) {
                removeEmitter(notification.username(), emitter);
            }
        }
    }

    @Transactional
    public List<CustomerNotification> pendingFor(String username) {
        List<ParkingReservation> confirmedReservations =
                reservationRepository.findByCustomerUsernameAndStatusInAndPaymentConfirmedAtIsNotNull(
                        username, List.of(ReservationStatus.CONFIRMED,
                                ReservationStatus.ARRIVED, ReservationStatus.COMPLETED));
        for (ParkingReservation reservation : confirmedReservations) {
            if (!notificationRepository.existsByReservationId(reservation.getId())) {
                notifyCustomer(username, reservation.getId(), "Đặt chỗ " + reservation.getBookingCode()
                        + " đã được xác nhận thanh toán thành công.");
            }
        }
        return notificationRepository.findAllByRecipientUsernameAndReadAtIsNullOrderByCreatedAtAsc(username);
    }

    @Transactional
    public boolean acknowledge(Long id, String username) {
        Optional<CustomerNotification> notification =
                notificationRepository.findByIdAndRecipientUsernameAndReadAtIsNull(id, username);
        if (notification.isEmpty()) {
            return false;
        }

        notification.get().setReadAt(LocalDateTime.now());
        notificationRepository.save(notification.get());
        return true;
    }

    private void removeEmitter(String username, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUsername.get(username);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByUsername.remove(username, emitters);
            }
        }
    }

    private record NotificationCreated(String username, Long id, String message) {}
}
