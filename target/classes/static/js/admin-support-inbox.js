(() => {
  const root = document.getElementById('support-inbox');
  const conversationList = document.getElementById('support-conversation-list');
  const messageList = document.getElementById('support-inbox-messages');
  const title = document.getElementById('support-conversation-title');
  const status = document.getElementById('support-inbox-status');
  const blockButton = document.getElementById('support-block-customer');
  const form = document.getElementById('support-admin-compose');
  const input = document.getElementById('support-admin-input');
  const imageInput = document.getElementById('support-admin-image');
  const imageName = document.getElementById('support-admin-image-name');
  const composeError = document.getElementById('support-admin-compose-error');
  const emptyState = document.getElementById('support-empty-state');
  if (!root || !conversationList || !messageList || !title || !status || !blockButton
      || !form || !input || !imageInput || !imageName || !composeError || !emptyState) return;

  const seenIds = new Set();
  const maxImageSize = 5 * 1024 * 1024;
  const allowedImageTypes = new Set(['image/jpeg', 'image/png', 'image/gif']);
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  let selectedUsername = '';
  let selectedBlocked = false;

  const request = async (url, options = {}) => {
    const headers = { ...(options.headers || {}) };
    if (csrfToken && csrfHeader && options.method === 'POST') headers[csrfHeader] = csrfToken;
    const response = await fetch(url, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      headers
    });
    if (!response.ok) throw new Error(`Yêu cầu thất bại (${response.status}).`);
    return response;
  };

  const renderMessage = (message) => {
    if (seenIds.has(message.id)) return;
    seenIds.add(message.id);
    const bubble = document.createElement('article');
    bubble.dataset.messageId = String(message.id);
    bubble.className = `support-message ${message.senderRole === 'CUSTOMER' ? 'is-customer' : 'is-admin'}`;
    const sender = document.createElement('strong');
    sender.textContent = message.senderRole === 'ADMIN' ? 'Bạn' : message.senderName;
    if (message.message) {
      const text = document.createElement('p');
      text.textContent = message.message;
      bubble.append(text);
    }
    if (message.imageUrl) {
      const image = document.createElement('img');
      image.className = 'support-message-image';
      image.src = message.imageUrl;
      image.alt = 'Ảnh khách hàng gửi';
      image.loading = 'lazy';
      bubble.append(image);
    }
    const time = document.createElement('time');
    time.textContent = message.createdAt;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'support-message-delete';
    remove.dataset.messageId = String(message.id);
    remove.textContent = 'Xóa';
    remove.setAttribute('aria-label', `Xóa tin nhắn lúc ${message.createdAt}`);
    bubble.prepend(sender);
    bubble.append(time, remove);
    messageList.append(bubble);
    messageList.scrollTop = messageList.scrollHeight;
  };

  const renderConversations = (conversations) => {
    conversationList.replaceChildren();
    for (const conversation of conversations) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `support-conversation${conversation.username === selectedUsername ? ' is-active' : ''}`;
      const name = document.createElement('strong');
      name.textContent = conversation.displayName;
      const preview = document.createElement('span');
      preview.textContent = conversation.lastMessage;
      const metadata = document.createElement('small');
      metadata.textContent = `${conversation.username} · ${conversation.lastMessageAt}`;
      button.append(name, preview, metadata);
      if (conversation.blocked) {
        const blocked = document.createElement('small');
        blocked.className = 'support-conversation-blocked';
        blocked.textContent = 'Đã chặn chat';
        button.append(blocked);
      }
      if (conversation.unreadCount > 0) {
        const unread = document.createElement('i');
        unread.className = 'support-unread-count';
        unread.textContent = String(conversation.unreadCount);
        button.append(unread);
      }
      button.addEventListener('click', () => selectConversation(
        conversation.username, conversation.displayName, conversation.blocked
      ));
      conversationList.append(button);
    }
    if (conversations.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'support-inbox-empty';
      empty.textContent = 'Chưa có cuộc trò chuyện nào.';
      conversationList.append(empty);
    }
  };

  const loadConversations = async () => {
    try {
      const response = await request('/admin/support/conversations');
      const conversations = await response.json();
      renderConversations(conversations);
      const selected = conversations.find((conversation) => conversation.username === selectedUsername);
      if (selected) {
        updateSelectedBlockState(selected.blocked);
      } else if (conversations.length > 0 && !selectedUsername) {
        selectConversation(conversations[0].username, conversations[0].displayName, conversations[0].blocked);
      }
    } catch (error) {
      status.textContent = 'Không thể tải hộp thư.';
      console.error('Không thể tải danh sách hội thoại hỗ trợ.', error);
    }
  };

  const updateSelectedBlockState = (blocked) => {
    selectedBlocked = blocked;
    blockButton.textContent = blocked ? 'Bỏ chặn khách hàng' : 'Chặn khách hàng';
    blockButton.classList.toggle('btn-outline-danger', !blocked);
    blockButton.classList.toggle('btn-outline-success', blocked);
    blockButton.disabled = false;
  };

  const selectConversation = async (username, displayName, blocked) => {
    selectedUsername = username;
    title.textContent = displayName;
    status.textContent = blocked ? `Khách hàng: ${username} · Đã chặn` : `Khách hàng: ${username}`;
    updateSelectedBlockState(blocked);
    blockButton.hidden = false;
    emptyState.hidden = true;
    form.hidden = false;
    imageInput.value = '';
    imageName.textContent = '';
    composeError.hidden = true;
    messageList.replaceChildren();
    seenIds.clear();
    try {
      const response = await request(
        `/admin/support/conversations/${encodeURIComponent(username)}/messages`
      );
      (await response.json()).forEach(renderMessage);
      window.dispatchEvent(new Event('support-messages-read'));
      await loadConversations();
    } catch (error) {
      status.textContent = 'Không thể tải nội dung hội thoại.';
      console.error('Không thể tải tin nhắn của khách hàng.', error);
    }
  };

  blockButton.addEventListener('click', async () => {
    if (!selectedUsername) return;
    const blocked = !selectedBlocked;
    if (blocked && !window.confirm(`Chặn ${title.textContent} gửi tin nhắn hỗ trợ?`)) return;
    blockButton.disabled = true;
    try {
      const response = await request(
        `/admin/support/conversations/${encodeURIComponent(selectedUsername)}/block`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ blocked })
        }
      );
      updateSelectedBlockState((await response.json()).blocked);
      status.textContent = `Khách hàng: ${selectedUsername}${blocked ? ' · Đã chặn' : ''}`;
      await loadConversations();
    } catch (error) {
      status.textContent = 'Không thể cập nhật trạng thái chặn.';
      blockButton.disabled = false;
      console.error('Không thể chặn hoặc bỏ chặn khách hàng.', error);
    }
  });

  messageList.addEventListener('click', async (event) => {
    const button = event.target.closest('.support-message-delete');
    if (!button || !selectedUsername) return;
    if (!window.confirm('Bạn có chắc muốn xóa tin nhắn này không?')) return;
    button.disabled = true;
    try {
      await request(
        `/admin/support/conversations/${encodeURIComponent(selectedUsername)}/messages/${button.dataset.messageId}/delete`,
        { method: 'POST' }
      );
    } catch (error) {
      button.disabled = false;
      status.textContent = 'Không thể xóa tin nhắn.';
      console.error('Không thể xóa tin nhắn hỗ trợ.', error);
    }
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const message = input.value.trim();
    const image = imageInput.files[0];
    if ((!message && !image) || !selectedUsername) return;
    if (image && (!allowedImageTypes.has(image.type) || image.size > maxImageSize)) {
      composeError.textContent = image.size > maxImageSize
        ? 'Ảnh không được vượt quá 5 MB.'
        : 'Chỉ chấp nhận ảnh JPEG, PNG hoặc GIF.';
      composeError.hidden = false;
      return;
    }
    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true;
    composeError.hidden = true;
    try {
      const messageUrl = `/admin/support/conversations/${encodeURIComponent(selectedUsername)}/messages`;
      const options = image
        ? (() => {
          const body = new FormData();
          body.append('message', message);
          body.append('image', image);
          return { method: 'POST', body };
        })()
        : {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message })
        };
      const response = await request(image ? `${messageUrl}/image` : messageUrl, options);
      renderMessage(await response.json());
      input.value = '';
      imageInput.value = '';
      imageName.textContent = '';
      await loadConversations();
    } catch (error) {
      composeError.textContent = 'Không gửi được tin nhắn hoặc ảnh. Vui lòng thử lại.';
      composeError.hidden = false;
      console.error('Không thể trả lời khách hàng.', error);
    } finally {
      submit.disabled = false;
    }
  });

  imageInput.addEventListener('change', () => {
    const image = imageInput.files[0];
    imageName.textContent = image ? image.name : '';
    composeError.hidden = true;
    if (image && (!allowedImageTypes.has(image.type) || image.size > maxImageSize)) {
      composeError.textContent = image.size > maxImageSize
        ? 'Ảnh không được vượt quá 5 MB.'
        : 'Chỉ chấp nhận ảnh JPEG, PNG hoặc GIF.';
      composeError.hidden = false;
    }
  });

  const eventSource = new EventSource('/admin/support/stream');
  eventSource.onopen = () => {
    status.textContent = selectedUsername ? `Khách hàng: ${selectedUsername}` : 'Đang trực tuyến';
  };
  eventSource.addEventListener('support-message', (event) => {
    try {
      const message = JSON.parse(event.data);
      loadConversations();
      if (message.customerUsername === selectedUsername) renderMessage(message);
      else if (message.senderRole === 'CUSTOMER') status.textContent = 'Có tin nhắn mới';
    } catch (error) {
      console.error('Không thể đọc tin nhắn hỗ trợ trực tiếp.', error);
    }
  });
  eventSource.addEventListener('support-message-deleted', (event) => {
    try {
      const deleted = JSON.parse(event.data);
      if (deleted.customerUsername !== selectedUsername) return;
      seenIds.delete(deleted.messageId);
      messageList.querySelector(`[data-message-id="${deleted.messageId}"]`)?.remove();
      loadConversations();
    } catch (error) {
      console.error('Không thể đồng bộ tin nhắn đã xóa.', error);
    }
  });

  loadConversations();
})();
