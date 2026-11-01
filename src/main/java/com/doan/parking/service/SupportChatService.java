package com.doan.parking.service;

import com.doan.parking.entity.AppUser;
import com.doan.parking.entity.SupportChatMessage;
import com.doan.parking.repository.SupportChatMessageRepository;
import com.doan.parking.repository.SupportChatMessageRepository.ConversationPreview;
import com.doan.parking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
public class SupportChatService {
    private static final long STREAM_TIMEOUT_MILLIS = 0L;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final SupportChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, List<SseEmitter>> customerEmitters = new ConcurrentHashMap<>();
    private final List<SseEmitter> adminEmitters = new CopyOnWriteArrayList<>();

    @Transactional
    public List<MessageResponse> customerMessages(String username) {
        requireCustomer(username);
        markRead(username, "ADMIN");
        return messageRepository.findByCustomerUsernameOrderByCreatedAtAscIdAsc(username).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<MessageResponse> adminMessages(String customerUsername) {
        requireCustomer(customerUsername);
        markRead(customerUsername, "CUSTOMER");
        return messageRepository.findByCustomerUsernameOrderByCreatedAtAscIdAsc(customerUsername).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isCustomerChatBlocked(String username) {
        return requireCustomer(username).isSupportChatBlocked();
    }

    @Transactional(readOnly = true)
    public long unreadAdminMessageCount(String username) {
        requireCustomer(username);
        return messageRepository.countByCustomerUsernameAndSenderRoleAndReadAtIsNull(username, "ADMIN");
    }

    @Transactional
    public boolean setCustomerChatBlocked(String username, boolean blocked) {
        AppUser customer = requireCustomer(username);
        customer.setSupportChatBlocked(blocked);
        userRepository.save(customer);
        return blocked;
    }

    @Transactional
    public void deleteMessage(String customerUsername, Long messageId) {
        requireCustomer(customerUsername);
        SupportChatMessage message = messageRepository.findById(messageId)
                .filter(item -> item.getCustomerUsername().equals(customerUsername))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tin nhắn."));
        messageRepository.delete(message);
        eventPublisher.publishEvent(new MessageDeleted(messageId, customerUsername));
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> conversations() {
        List<ConversationResponse> conversations = new ArrayList<>();
        Set<String> includedCustomers = new HashSet<>();
        List<AppUser> customers = userRepository.findByRoleOrderByUsernameAsc("CUSTOMER");
        Map<String, AppUser> customersByUsername = new HashMap<>();
        for (AppUser customer : customers) {
            customersByUsername.put(customer.getUsername(), customer);
        }
        for (ConversationPreview preview : messageRepository.findConversationPreviews()) {
            AppUser customer = customersByUsername.get(preview.getCustomerUsername());
            if (customer == null) {
                continue;
            }
            includedCustomers.add(customer.getUsername());
            conversations.add(new ConversationResponse(
                    customer.getUsername(),
                    displayName(customer),
                    preview.getLastMessage() == null || preview.getLastMessage().isBlank()
                            ? "Hình ảnh" : preview.getLastMessage(),
                    formatTime(preview.getLastMessageAt()),
                    messageRepository.countByCustomerUsernameAndSenderRoleAndReadAtIsNull(
                            customer.getUsername(), "CUSTOMER"),
                    customer.isSupportChatBlocked()));
        }
        customers.stream()
                .filter(customer -> customer.isSupportChatBlocked()
                        && includedCustomers.add(customer.getUsername()))
                .map(customer -> new ConversationResponse(customer.getUsername(), displayName(customer),
                        "Chưa có tin nhắn", "", 0, true))
                .forEach(conversations::add);
        return conversations;
    }

    @Transactional
    public MessageResponse sendFromCustomer(String username, String message) {
        if (requireCustomer(username).isSupportChatBlocked()) {
            throw new IllegalStateException("Tài khoản của bạn hiện không thể gửi tin nhắn hỗ trợ.");
        }
        return saveMessage(username, username, "CUSTOMER", message);
    }

    @Transactional
    public MessageResponse replyFromAdmin(String adminUsername, String customerUsername, String message) {
        requireCustomer(customerUsername);
        return saveMessage(customerUsername, adminUsername, "ADMIN", message);
    }

    @Transactional
    public MessageResponse replyFromAdminWithImage(String adminUsername, String customerUsername, String message,
                                                   byte[] imageData, String imageContentType) {
        requireCustomer(customerUsername);
        String trimmedMessage = message == null ? "" : message.trim();
        if (trimmedMessage.length() > MAX_MESSAGE_LENGTH
                || (trimmedMessage.isEmpty() && (imageData == null || imageData.length == 0))) {
            throw new IllegalArgumentException("Tin nhắn cần có nội dung hoặc ảnh và tối đa 2000 ký tự.");
        }
        return saveMessage(customerUsername, adminUsername, "ADMIN", trimmedMessage, imageData, imageContentType);
    }

    @Transactional(readOnly = true)
    public MessageImage imageForViewer(String username, boolean admin, Long messageId) {
        SupportChatMessage message;
        if (admin) {
            message = messageRepository.findById(messageId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ảnh."));
        } else {
            message = messageRepository.findByIdAndCustomerUsername(messageId, username)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy ảnh."));
        }
        if (message.getImageData() == null || message.getImageContentType() == null) {
            throw new IllegalArgumentException("Không tìm thấy ảnh.");
        }
        return new MessageImage(message.getImageData(), message.getImageContentType());
    }

    public SseEmitter subscribeCustomer(String username) {
        requireCustomer(username);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        List<SseEmitter> emitters = customerEmitters.computeIfAbsent(
                username, ignored -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> removeCustomerEmitter(username, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            removeCustomerEmitter(username, emitter);
        });
        emitter.onError(error -> removeCustomerEmitter(username, emitter));
        return emitter;
    }

    public SseEmitter subscribeAdmin() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        adminEmitters.add(emitter);
        emitter.onCompletion(() -> adminEmitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            adminEmitters.remove(emitter);
        });
        emitter.onError(error -> adminEmitters.remove(emitter));
        return emitter;
    }

    public boolean isAdminOnline() {
        return !adminEmitters.isEmpty();
    }

    @Transactional(readOnly = true)
    public long unreadCustomerMessageCount() {
        return messageRepository.countBySenderRoleAndReadAtIsNull("CUSTOMER");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(MessageCreated created) {
        List<SseEmitter> customerStreams = customerEmitters.get(created.message().customerUsername());
        if (customerStreams != null) {
            for (SseEmitter emitter : customerStreams) {
                sendEvent(emitter, created.message());
            }
        }
        for (SseEmitter emitter : adminEmitters) {
            sendEvent(emitter, created.message());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishDeletionAfterCommit(MessageDeleted deleted) {
        List<SseEmitter> customerStreams = customerEmitters.get(deleted.customerUsername());
        if (customerStreams != null) {
            for (SseEmitter emitter : customerStreams) {
                sendDeletionEvent(emitter, deleted);
            }
        }
        for (SseEmitter emitter : adminEmitters) {
            sendDeletionEvent(emitter, deleted);
        }
    }

    private MessageResponse saveMessage(String customerUsername, String senderUsername,
                                        String senderRole, String message) {
        return saveMessage(customerUsername, senderUsername, senderRole, message, null, null);
    }

    private MessageResponse saveMessage(String customerUsername, String senderUsername, String senderRole,
                                        String message, byte[] imageData, String imageContentType) {
        String trimmedMessage = message == null ? "" : message.trim();
        if ((trimmedMessage.isEmpty() && (imageData == null || imageData.length == 0))
                || trimmedMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Tin nhắn phải có từ 1 đến 2000 ký tự.");
        }

        LocalDateTime now = LocalDateTime.now();
        SupportChatMessage chatMessage = new SupportChatMessage();
        chatMessage.setCustomerUsername(customerUsername);
        chatMessage.setSenderUsername(senderUsername);
        chatMessage.setSenderRole(senderRole);
        chatMessage.setMessage(trimmedMessage);
        chatMessage.setImageData(imageData);
        chatMessage.setImageContentType(imageContentType);
        chatMessage.setCreatedAt(now);
        MessageResponse response = toResponse(messageRepository.save(chatMessage));
        eventPublisher.publishEvent(new MessageCreated(response));
        return response;
    }

    private void markRead(String customerUsername, String senderRole) {
        messageRepository.markUnreadMessagesRead(customerUsername, senderRole, LocalDateTime.now());
    }

    private AppUser requireCustomer(String username) {
        AppUser user = userRepository.findByUsername(username)
                .filter(account -> "CUSTOMER".equalsIgnoreCase(account.getRole()))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khách hàng."));
        return user;
    }

    private MessageResponse toResponse(SupportChatMessage message) {
        AppUser sender = userRepository.findByUsername(message.getSenderUsername()).orElse(null);
        String senderName = sender == null || sender.getFullName() == null || sender.getFullName().isBlank()
                ? message.getSenderUsername() : sender.getFullName();
        return new MessageResponse(
                message.getId(),
                message.getCustomerUsername(),
                message.getSenderUsername(),
                message.getSenderRole(),
                senderName,
                message.getMessage(),
                message.getImageData() == null ? null : "/support/messages/" + message.getId() + "/image",
                formatTime(message.getCreatedAt()));
    }

    private String formatTime(LocalDateTime dateTime) {
        return dateTime.format(DISPLAY_TIME);
    }

    private String displayName(AppUser customer) {
        return customer.getFullName() == null || customer.getFullName().isBlank()
                ? customer.getUsername() : customer.getFullName();
    }

    private void sendEvent(SseEmitter emitter, MessageResponse message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("support-message")
                    .id(message.id().toString())
                    .data(message));
        } catch (IOException | IllegalStateException exception) {
            emitter.complete();
            adminEmitters.remove(emitter);
            customerEmitters.forEach((username, emitters) -> {
                emitters.remove(emitter);
                if (emitters.isEmpty()) {
                    customerEmitters.remove(username, emitters);
                }
            });
        }
    }

    private void sendDeletionEvent(SseEmitter emitter, MessageDeleted deleted) {
        try {
            emitter.send(SseEmitter.event()
                    .name("support-message-deleted")
                    .id(deleted.messageId().toString())
                    .data(deleted));
        } catch (IOException | IllegalStateException exception) {
            emitter.complete();
            adminEmitters.remove(emitter);
            customerEmitters.forEach((username, emitters) -> {
                emitters.remove(emitter);
                if (emitters.isEmpty()) {
                    customerEmitters.remove(username, emitters);
                }
            });
        }
    }

    private void removeCustomerEmitter(String username, SseEmitter emitter) {
        List<SseEmitter> emitters = customerEmitters.get(username);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                customerEmitters.remove(username, emitters);
            }
        }
    }

    public record MessageResponse(Long id, String customerUsername, String senderUsername,
                                  String senderRole, String senderName, String message,
                                  String imageUrl, String createdAt) {}

    public record MessageImage(byte[] data, String contentType) {}

    public record ConversationResponse(String username, String displayName, String lastMessage,
                                       String lastMessageAt, long unreadCount, boolean blocked) {}

    public record MessageCreated(MessageResponse message) {}

    public record MessageDeleted(Long messageId, String customerUsername) {}
}
