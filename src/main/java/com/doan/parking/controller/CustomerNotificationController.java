package com.doan.parking.controller;

import com.doan.parking.service.CustomerNotificationService;
import com.doan.parking.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class CustomerNotificationController {
    private final CustomerNotificationService notificationService;
    private final ReservationService reservationService;

    public record NotificationResponse(Long id, String message) {}
    public record ReservationStatusResponse(String bookingCode, String status, String statusLabel) {}

    @GetMapping("/customer/notifications/stream")
    @PreAuthorize("hasRole('CUSTOMER')")
    public SseEmitter stream(Principal principal) {
        return notificationService.subscribe(principal.getName());
    }

    @GetMapping("/customer/notifications/pending")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<NotificationResponse> pending(Principal principal) {
        return notificationService.pendingFor(principal.getName()).stream()
                .map(notification -> new NotificationResponse(notification.getId(), notification.getMessage()))
                .toList();
    }

    @GetMapping("/customer/reservations/status")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<ReservationStatusResponse> reservationStatuses(Principal principal) {
        return reservationService.customerBookings(principal.getName()).stream()
                .map(reservation -> new ReservationStatusResponse(
                        reservation.getBookingCode(),
                        reservation.getStatus().name(),
                        reservation.getStatus().getLabel()))
                .toList();
    }

    @PostMapping("/customer/notifications/{id}/acknowledge")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Void> acknowledge(@PathVariable Long id, Principal principal) {
        return notificationService.acknowledge(id, principal.getName())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
