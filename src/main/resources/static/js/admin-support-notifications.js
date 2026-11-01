(() => {
  const badge = document.getElementById('support-nav-unread');
  if (!badge) return;

  const updateUnreadCount = async () => {
    try {
      const response = await fetch('/admin/support/unread', {
        credentials: 'same-origin',
        cache: 'no-store'
      });
      if (!response.ok) throw new Error(`Yêu cầu thất bại (${response.status}).`);
      const { count } = await response.json();
      badge.textContent = count > 99 ? '99+' : String(count);
      badge.hidden = count <= 0;
      badge.setAttribute('aria-label', `${count} tin nhắn chưa đọc`);
    } catch (error) {
      console.error('Không thể tải số tin nhắn hỗ trợ chưa đọc.', error);
    }
  };

  const eventSource = new EventSource('/admin/support/stream');
  eventSource.addEventListener('support-message', (event) => {
    try {
      if (JSON.parse(event.data).senderRole === 'CUSTOMER') updateUnreadCount();
    } catch (error) {
      console.error('Không thể đọc thông báo tin nhắn hỗ trợ.', error);
    }
  });

  window.addEventListener('support-messages-read', updateUnreadCount);
  updateUnreadCount();
})();
