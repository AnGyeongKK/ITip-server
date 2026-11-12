package com.gkk.itip.domain.chat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChatMessage m where m.id=:id")
    Optional<ChatMessage> lock(@Param("id") UUID id);
    Optional<ChatMessage> findByIdAndConversationId(UUID id, UUID conversationId);
    List<ChatMessage> findByConversationIdAndPositionLessThanOrderByPositionDesc(UUID conversationId, long before, Pageable page);
    List<ChatMessage> findByConversationIdAndClientRequestIdOrderByPositionAsc(UUID conversationId, UUID clientRequestId);
    List<ChatMessage> findAllByConversationIdInAndStatusIn(Collection<UUID> conversations, Collection<MessageStatus> statuses);
    boolean existsByConversationIdAndStatusIn(UUID conversationId, Collection<MessageStatus> statuses);
}
