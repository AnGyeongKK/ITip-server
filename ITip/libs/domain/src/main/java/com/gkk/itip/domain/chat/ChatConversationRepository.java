package com.gkk.itip.domain.chat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {
    Optional<ChatConversation> findByIdAndOwnerId(UUID id, String ownerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatConversation c where c.id=:id and c.ownerId=:owner")
    Optional<ChatConversation> lockOwned(@Param("id") UUID id, @Param("owner") String owner);
    List<ChatConversation> findByOwnerIdOrderByUpdatedAtDescIdDesc(String ownerId, Pageable page);
    @Query("""
            select c from ChatConversation c where c.ownerId=:owner
              and (c.updatedAt<:at or (c.updatedAt=:at and c.id<:id))
            order by c.updatedAt desc,c.id desc
            """)
    List<ChatConversation> before(@Param("owner") String owner, @Param("at") Instant at, @Param("id") UUID id, Pageable page);
}
