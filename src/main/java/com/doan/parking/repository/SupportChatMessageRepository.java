package com.doan.parking.repository;

import com.doan.parking.entity.SupportChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportChatMessageRepository extends JpaRepository<SupportChatMessage, Long> {
    List<SupportChatMessage> findByCustomerUsernameOrderByCreatedAtAscIdAsc(String customerUsername);

    Optional<SupportChatMessage> findByIdAndCustomerUsername(Long id, String customerUsername);

    @Query("""
            select message.customerUsername as customerUsername,
                   message.message as lastMessage,
                   message.createdAt as lastMessageAt
            from SupportChatMessage message
            where message.id = (
                select max(latest.id) from SupportChatMessage latest
                where latest.customerUsername = message.customerUsername
            )
            order by message.createdAt desc
            """)
    List<ConversationPreview> findConversationPreviews();

    long countByCustomerUsernameAndSenderRoleAndReadAtIsNull(String customerUsername, String senderRole);

    long countBySenderRoleAndReadAtIsNull(String senderRole);

    @Modifying
    @Query("""
            update SupportChatMessage message
            set message.readAt = :readAt
            where message.customerUsername = :customerUsername
              and message.senderRole = :senderRole
              and message.readAt is null
            """)
    int markUnreadMessagesRead(@Param("customerUsername") String customerUsername,
                               @Param("senderRole") String senderRole,
                               @Param("readAt") LocalDateTime readAt);

    interface ConversationPreview {
        String getCustomerUsername();
        String getLastMessage();
        LocalDateTime getLastMessageAt();
    }
}
