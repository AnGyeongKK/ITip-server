package com.gkk.itip.domain.chat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    Optional<ChatMessage> findByIdAndConversationId(UUID id, UUID conversationId);
    List<ChatMessage> findByConversationIdAndPositionLessThanOrderByPositionDesc(UUID conversationId, long before, Pageable page);
    List<ChatMessage> findByConversationIdAndClientRequestIdOrderByPositionAsc(UUID conversationId, UUID clientRequestId);
}
