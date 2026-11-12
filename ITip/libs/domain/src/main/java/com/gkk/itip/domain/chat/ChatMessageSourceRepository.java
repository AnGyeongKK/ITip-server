package com.gkk.itip.domain.chat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
public interface ChatMessageSourceRepository extends JpaRepository<ChatMessageSource, UUID> {
    List<ChatMessageSource> findAllByMessageIdInOrderBySourceIdAsc(Collection<UUID> messageIds);
}
