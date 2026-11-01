package com.doan.parking.controller;

import com.doan.parking.service.SupportChatService;
import com.doan.parking.service.SupportChatService.ConversationResponse;
import com.doan.parking.service.SupportChatService.MessageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.Principal;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Controller
@RequiredArgsConstructor
public class SupportChatController {
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final long MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 20_000_000;

    private final SupportChatService chatService;

    @GetMapping("/admin/support/inbox")
    @PreAuthorize("hasRole('ADMIN')")
    public String inbox() {
        return "support-inbox";
    }

    @GetMapping("/customer/support/messages")
    @ResponseBody
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<MessageResponse> customerMessages(Principal principal) {
        return chatService.customerMessages(principal.getName());
    }

    @GetMapping("/customer/support/stream")
    @ResponseBody
    @PreAuthorize("hasRole('CUSTOMER')")
    public SseEmitter customerStream(Principal principal) {
        return chatService.subscribeCustomer(principal.getName());
    }

    @GetMapping("/customer/support/status")
    @ResponseBody
    @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String, Object> supportStatus(Principal principal) {
        return Map.of(
                "adminOnline", chatService.isAdminOnline(),
                "chatBlocked", chatService.isCustomerChatBlocked(principal.getName()),
                "unreadCount", chatService.unreadAdminMessageCount(principal.getName()));
    }

    @PostMapping("/customer/support/messages")
    @ResponseBody
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<?> sendCustomerMessage(@RequestBody(required = false) MessageRequest request,
                                                 Principal principal) {
        if (request == null || !validMessage(request.message())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tin nhắn phải có từ 1 đến 2000 ký tự."));
        }
        try {
            return ResponseEntity.ok(chatService.sendFromCustomer(principal.getName(), request.message()));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/support/messages/{messageId}/image")
    @ResponseBody
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    public ResponseEntity<?> messageImage(@PathVariable Long messageId, Principal principal,
                                          Authentication authentication) {
        try {
            boolean admin = authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
            SupportChatService.MessageImage image = chatService.imageForViewer(principal.getName(), admin, messageId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(image.data());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/admin/support/conversations")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public List<ConversationResponse> conversations() {
        return chatService.conversations();
    }

    @GetMapping("/admin/support/unread")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Long> unreadCustomerMessages() {
        return Map.of("count", chatService.unreadCustomerMessageCount());
    }

    @GetMapping("/admin/support/conversations/{username}/messages")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> adminMessages(@PathVariable String username) {
        try {
            return ResponseEntity.ok(chatService.adminMessages(username));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/admin/support/conversations/{username}/messages")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> replyToCustomer(@PathVariable String username,
                                             @RequestBody(required = false) MessageRequest request,
                                             Principal principal) {
        if (request == null || !validMessage(request.message())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tin nhắn phải có từ 1 đến 2000 ký tự."));
        }
        try {
            return ResponseEntity.ok(
                    chatService.replyFromAdmin(principal.getName(), username, request.message()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/admin/support/conversations/{username}/messages/image")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> replyWithImage(@PathVariable String username,
                                            @RequestParam(value = "message", required = false) String message,
                                            @RequestParam(value = "image", required = false) MultipartFile image,
                                            Principal principal) {
        if (message != null && message.trim().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "Tin nhắn tối đa 2000 ký tự."));
        }
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng chọn ảnh để gửi."));
        }
        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ảnh không được vượt quá 5 MB."));
        }
        byte[] imageData;
        String contentType;
        try {
            imageData = image.getBytes();
            contentType = validateImage(imageData);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (IOException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", "Không thể đọc tệp ảnh."));
        }
        try {
            return ResponseEntity.ok(chatService.replyFromAdminWithImage(
                    principal.getName(), username, message, imageData, contentType));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/admin/support/conversations/{username}/block")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setCustomerBlocked(@PathVariable String username,
                                                @RequestBody(required = false) BlockRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thiếu trạng thái chặn."));
        }
        try {
            boolean blocked = chatService.setCustomerChatBlocked(username, request.blocked());
            return ResponseEntity.ok(Map.of("blocked", blocked));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/admin/support/conversations/{username}/messages/{messageId}/delete")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteMessage(@PathVariable String username, @PathVariable Long messageId) {
        try {
            chatService.deleteMessage(username, messageId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/admin/support/stream")
    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public SseEmitter adminStream() {
        return chatService.subscribeAdmin();
    }

    private boolean validMessage(String message) {
        return message != null && !message.isBlank() && message.trim().length() <= MAX_MESSAGE_LENGTH;
    }

    private String validateImage(byte[] data) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (stream == null) {
                throw new IllegalArgumentException("Tệp tải lên không phải ảnh JPEG, PNG hoặc GIF.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Tệp tải lên không phải ảnh JPEG, PNG hoặc GIF.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String contentType = switch (format) {
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "gif" -> "image/gif";
                    default -> throw new IllegalArgumentException("Chỉ chấp nhận ảnh JPEG, PNG hoặc GIF.");
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IllegalArgumentException("Kích thước ảnh quá lớn.");
                }
                reader.read(0);
                return contentType;
            } finally {
                reader.dispose();
            }
        }
    }

    public record MessageRequest(String message) {}

    public record BlockRequest(boolean blocked) {}
}
