(() => {
  const launcher = document.getElementById('support-launcher');
  const panel = document.getElementById('support-panel');
  const messages = document.getElementById('support-messages');
  const form = document.getElementById('support-compose');
  const input = document.getElementById('support-input');
  const presence = document.getElementById('support-presence');
  const errorMessage = document.getElementById('support-error');
  const unreadBadge = document.getElementById('support-launcher-unread');
  if (!launcher || !panel || !messages || !form || !input || !presence || !errorMessage || !unreadBadge) return;

  const seenIds = new Set();
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  let chatBlocked = false;

  const setUnreadCount = (count) => {
    const unreadCount = Math.max(0, Number(count) || 0);
    unreadBadge.textContent = unreadCount > 99 ? '99+' : String(unreadCount);
    unreadBadge.hidden = unreadCount === 0;
    launcher.setAttribute('aria-label', unreadCount > 0
      ? `Chat hỗ trợ, ${unreadCount} tin nhắn chưa đọc`
      : 'Chat hỗ trợ');
  };

  const setOpen = (open) => {
    panel.hidden = !open;
    launcher.setAttribute('aria-expanded', String(open));
    if (open) {
      input.focus();
      messages.scrollTop = messages.scrollHeight;
      loadMessages();
    }
  };

  const appendMessage = (message) => {
    if (seenIds.has(message.id)) return;
    seenIds.add(message.id);

    const bubble = document.createElement('article');
    bubble.dataset.messageId = String(message.id);
    bubble.className = `support-message ${message.senderRole === 'CUSTOMER' ? 'is-customer' : 'is-admin'}`;
    if (message.message) {
      const text = document.createElement('p');
      text.textContent = message.message;
      bubble.append(text);
    }
    if (message.imageUrl) {
      const image = document.createElement('img');
      image.className = 'support-message-image';
      image.src = message.imageUrl;
      image.alt = 'Ảnh được gửi trong cuộc trò chuyện';
      image.loading = 'lazy';
      bubble.append(image);
    }
    const time = document.createElement('time');
    time.textContent = message.createdAt;
    bubble.append(time);
    messages.append(bubble);
    messages.scrollTop = messages.scrollHeight;
  };

  const request = async (url, options = {}) => {
    const headers = { ...(options.headers || {}) };
    if (csrfToken && csrfHeader && options.method === 'POST') headers[csrfHeader] = csrfToken;
    const response = await fetch(url, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      headers
    });
    if (!response.ok) throw new Error(`Yêu cầu chat thất bại (${response.status}).`);
    return response;
  };

  const loadMessages = async () => {
    try {
      const response = await request('/customer/support/messages');
      (await response.json()).forEach(appendMessage);
      setUnreadCount(0);
    } catch (error) {
      errorMessage.textContent = 'Không thể tải lịch sử chat. Vui lòng tải lại trang.';
      errorMessage.hidden = false;
      console.error('Không thể tải lịch sử chat hỗ trợ.', error);
    }
  };

  const checkPresence = async () => {
    try {
      const response = await request('/customer/support/status');
      const status = await response.json();
      const submit = form.querySelector('button[type="submit"]');
      chatBlocked = status.chatBlocked;
      setUnreadCount(status.unreadCount);
      input.disabled = chatBlocked;
      submit.disabled = chatBlocked;
      document.querySelectorAll('[data-quick-message]').forEach((button) => {
        button.disabled = chatBlocked;
      });
      presence.textContent = chatBlocked
        ? 'Bạn hiện không thể gửi tin nhắn hỗ trợ'
        : status.adminOnline
          ? 'Quản trị viên đang trực tuyến'
          : 'Ngoại tuyến · tin nhắn sẽ được lưu';
      errorMessage.hidden = !chatBlocked;
      if (chatBlocked) errorMessage.textContent = 'Quản trị viên đã tạm chặn tin nhắn hỗ trợ của bạn.';
    } catch (error) {
      presence.textContent = 'Không thể kiểm tra trạng thái';
      console.error('Không thể kiểm tra trạng thái hỗ trợ.', error);
    }
  };

  launcher.addEventListener('click', () => setOpen(panel.hidden));
  document.getElementById('support-close')?.addEventListener('click', () => setOpen(false));

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const message = input.value.trim();
    if (!message) return;
    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true;
    errorMessage.hidden = true;
    try {
      const response = await request('/customer/support/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message })
      });
      appendMessage(await response.json());
      input.value = '';
    } catch (error) {
      errorMessage.textContent = 'Không gửi được tin nhắn. Vui lòng thử lại.';
      errorMessage.hidden = false;
      console.error('Không thể gửi tin nhắn hỗ trợ.', error);
      await checkPresence();
    } finally {
      submit.disabled = chatBlocked;
      input.focus();
    }
  });

  document.querySelectorAll('[data-quick-message]').forEach((button) => {
    button.addEventListener('click', () => {
      input.value = button.dataset.quickMessage;
      form.requestSubmit();
    });
  });

  const eventSource = new EventSource('/customer/support/stream');
  eventSource.addEventListener('support-message', (event) => {
    try {
      const message = JSON.parse(event.data);
      appendMessage(message);
      if (message.senderRole === 'ADMIN') {
        if (panel.hidden) checkPresence();
        else loadMessages();
      }
    } catch (error) {
      console.error('Không thể đọc tin nhắn hỗ trợ trực tiếp.', error);
    }
  });
  eventSource.addEventListener('support-message-deleted', (event) => {
    try {
      const deleted = JSON.parse(event.data);
      seenIds.delete(deleted.messageId);
      messages.querySelector(`[data-message-id="${deleted.messageId}"]`)?.remove();
    } catch (error) {
      console.error('Không thể đồng bộ tin nhắn đã xóa.', error);
    }
  });

  checkPresence();
  window.setInterval(checkPresence, 30000);
})();
