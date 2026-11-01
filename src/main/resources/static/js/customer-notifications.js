(() => {
  const container = document.getElementById('user-notifications');
  if (!container) return;

  const displayedIds = new Set();
  const acknowledgedIds = new Set();
  const displayedMessages = new Set();
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

  const dismiss = (notification) => {
    notification.classList.add('user-notification-hidden');
    window.setTimeout(() => notification.remove(), 250);
  };

  const showNotification = (messageText) => {
    if (displayedMessages.has(messageText)) return;
    displayedMessages.add(messageText);

    const notification = document.createElement('div');
    notification.className = 'alert alert-success user-notification';
    notification.setAttribute('role', 'status');

    const message = document.createElement('span');
    message.textContent = messageText;
    notification.append(message);

    const button = document.createElement('button');
    button.className = 'user-notification-close';
    button.type = 'button';
    button.setAttribute('aria-label', 'Đóng thông báo');
    const icon = document.createElement('i');
    icon.className = 'bi bi-x-lg';
    icon.setAttribute('aria-hidden', 'true');
    button.append(icon);
    button.addEventListener('click', () => dismiss(notification));
    notification.append(button);
    container.append(notification);

    window.setTimeout(() => dismiss(notification), 10000);
  };

  const processNotification = async (notification) => {
    if (!displayedIds.has(notification.id)) {
      showNotification(notification.message);
      displayedIds.add(notification.id);
    }
    if (!acknowledgedIds.has(notification.id)) {
      await acknowledge(notification.id);
    }
  };

  const acknowledge = async (id) => {
    const headers = {};
    if (csrfToken && csrfHeader) {
      headers[csrfHeader] = csrfToken;
    }
    const response = await fetch(`/customer/notifications/${encodeURIComponent(id)}/acknowledge`, {
      method: 'POST',
      credentials: 'same-origin',
      headers
    });
    if (!response.ok && response.status !== 404) {
      throw new Error(`Notification acknowledgement failed: ${response.status}`);
    }
    acknowledgedIds.add(id);
  };

  const pollNotifications = async () => {
    try {
      const response = await fetch('/customer/notifications/pending', {
        cache: 'no-store',
        credentials: 'same-origin'
      });
      if (!response.ok) {
        throw new Error(`Notification request failed: ${response.status}`);
      }

      const notifications = await response.json();
      for (const notification of notifications) {
        await processNotification(notification);
      }
    } catch (error) {
      console.error('Không thể tải thông báo đặt chỗ.', error);
    } finally {
      window.setTimeout(pollNotifications, 2000);
    }
  };

  const eventSource = new EventSource('/customer/notifications/stream');
  eventSource.addEventListener('customer-notification', (event) => {
    try {
      processNotification(JSON.parse(event.data)).catch((error) => {
        console.error('Không thể hiển thị thông báo xác nhận đặt chỗ.', error);
      });
    } catch (error) {
      console.error('Không thể đọc thông báo xác nhận đặt chỗ.', error);
    }
  });

  const pollReservationStatuses = async () => {
    try {
      const response = await fetch('/customer/reservations/status', {
        cache: 'no-store',
        credentials: 'same-origin'
      });
      if (!response.ok) {
        throw new Error(`Reservation status request failed: ${response.status}`);
      }

      const statuses = await response.json();
      for (const reservation of statuses) {
        const row = [...document.querySelectorAll('[data-booking-code]')]
          .find((element) => element.dataset.bookingCode === reservation.bookingCode);
        const badge = row?.querySelector('[data-reservation-status]');
        if (!row || !badge) continue;

        const previousStatus = row.dataset.reservationStatus;
        if (previousStatus === 'PENDING_PAYMENT'
            && ['CONFIRMED', 'ARRIVED', 'COMPLETED'].includes(reservation.status)) {
          showNotification(
            `Đặt chỗ ${reservation.bookingCode} đã được xác nhận thanh toán thành công.`
          );
        }
        row.dataset.reservationStatus = reservation.status;

        badge.textContent = reservation.statusLabel;
        badge.classList.remove('status-confirmed', 'status-cancelled', 'status-pending');
        if (['CONFIRMED', 'ARRIVED', 'COMPLETED'].includes(reservation.status)) {
          badge.classList.add('status-confirmed');
        } else if (reservation.status === 'CANCELLED') {
          badge.classList.add('status-cancelled');
        } else {
          badge.classList.add('status-pending');
        }

        const cancelForm = row.querySelector('form');
        if (cancelForm) {
          cancelForm.hidden = reservation.status !== 'PENDING_PAYMENT';
        }
      }
    } catch (error) {
      console.error('Không thể cập nhật trạng thái đặt chỗ.', error);
    } finally {
      window.setTimeout(pollReservationStatuses, 3000);
    }
  };

  pollNotifications();
  pollReservationStatuses();
})();
