package com.gkk.itip.domain.chat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {
    Optional<ChatConversation> findByIdAndOwnerId(UUID id, String ownerId);
}
