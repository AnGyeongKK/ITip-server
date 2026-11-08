package com.gkk.itip.chat.conversation.service;

import com.gkk.itip.chat.conversation.dto.*;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.request.RequestFingerprint;
import com.gkk.itip.domain.analysis.IdempotencyRequest;
import com.gkk.itip.domain.analysis.IdempotencyRequestRepository;
import com.gkk.itip.domain.chat.ChatConversation;
import com.gkk.itip.domain.chat.ChatConversationRepository;
import com.gkk.itip.domain.chat.ChatMessageRepository;
import com.gkk.itip.domain.chat.MessageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static com.gkk.itip.common.exception.ErrorCode.*;

@Service
public class ConversationService {
    private static final String CREATE = "CREATE_CHAT_CONVERSATION";
    private final ChatConversationRepository rooms;
    private final ChatMessageRepository messages;
    private final IdempotencyRequestRepository requests;
    private final ChatScopeQuery scopes;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Clock clock;
    public ConversationService(ChatConversationRepository rooms, ChatMessageRepository messages, IdempotencyRequestRepository requests,
            ChatScopeQuery scopes, JdbcTemplate jdbc, JsonMapper mapper, Clock clock) {
        this.rooms = rooms; this.messages = messages; this.requests = requests; this.scopes = scopes; this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public ConversationCreated create(String owner, UUID key, CreateConversationRequest request) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", "itip-chat-create:" + owner);
        String hash = RequestFingerprint.of(mapper, request);
        var previous = requests.findByOwnerIdAndOperationAndKey(owner, CREATE, key).orElse(null);
        if (previous != null) {
            if (!previous.getRequestHash().equals(hash)) throw new BusinessException(IDEMPOTENCY_CONFLICT);
            var room = rooms.findByIdAndOwnerId(previous.getTargetId(), owner).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
            return created(room, request.scope());
        }
        var selection = scopes.resolve(owner, request.scope());
        if (selection.status().equals("UNAVAILABLE")) throw new BusinessException(RESOURCE_NOT_FOUND);
        if (selection.records().isEmpty()) throw new BusinessException(EMPTY_CHAT_SCOPE);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var room = rooms.saveAndFlush(ChatConversation.create(owner, mapper.writeValueAsString(request.scope()),
                selection.label(), selection.records().size(), now));
        requests.save(new IdempotencyRequest(owner, CREATE, key, hash, room.getId(), now));
        return created(room, request.scope());
    }
    private static ConversationCreated created(ChatConversation room, ChatScope scope) {
        return new ConversationCreated(room.getId(), scope, room.getScopeLabel(), room.getInitialRecordCount(), room.getCreatedAt(), null);
    }
    @Transactional
    public void delete(String owner, UUID id) {
        var room = rooms.lockOwned(id, owner).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (messages.existsByConversationIdAndStatusIn(id, MessageStatus.ACTIVE)) throw new BusinessException(CHAT_GENERATION_IN_PROGRESS);
        rooms.delete(room);
        rooms.flush();
    }
}
