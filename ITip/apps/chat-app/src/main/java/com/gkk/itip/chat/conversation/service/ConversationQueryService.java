package com.gkk.itip.chat.conversation.service;

import com.gkk.itip.chat.conversation.dto.ConversationView;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.chat.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static com.gkk.itip.common.exception.ErrorCode.*;

@Service @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ConversationQueryService {
    private final ChatConversationRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatScopeQuery scopes;
    public ConversationQueryService(ChatConversationRepository rooms, ChatMessageRepository messages, ChatScopeQuery scopes) {
        this.rooms = rooms; this.messages = messages; this.scopes = scopes;
    }
    public ConversationView.Page list(String owner, String cursor, int limit) {
        var page = PageRequest.of(0, limit + 1);
        List<ChatConversation> found;
        if (cursor == null) found = rooms.findByOwnerIdOrderByUpdatedAtDescIdDesc(owner, page);
        else {
            var before = decode(cursor);
            found = rooms.before(owner, before.at(), before.id(), page);
        }
        boolean more = found.size() > limit;
        var selected = found.subList(0, Math.min(found.size(), limit));
        var active = active(selected.stream().map(ChatConversation::getId).toList());
        var items = selected.stream().map(c -> new ConversationView.Brief(c.getId(), c.getTitle(), c.getLastQuestion(),
                c.getScopeLabel(), active.get(c.getId()), c.getUpdatedAt())).toList();
        String next = more ? encode(selected.getLast()) : null;
        return new ConversationView.Page(items, next, more);
    }
    public ConversationView.Detail get(String owner, UUID roomId) {
        var room = rooms.findByIdAndOwnerId(roomId, owner).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        var scope = scopes.readScope(room.getScopeJson());
        var selection = scopes.resolve(owner, scope);
        return new ConversationView.Detail(roomId, scope, room.getScopeLabel(), selection.records().size(), selection.status(),
                active(List.of(roomId)).get(roomId), room.getCreatedAt(), room.getUpdatedAt());
    }
    private Map<UUID, UUID> active(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return messages.findAllByConversationIdInAndStatusIn(ids, MessageStatus.ACTIVE).stream()
                .collect(Collectors.toMap(ChatMessage::getConversationId, ChatMessage::getId));
    }
    private record Cursor(Instant at, UUID id) {}
    private static String encode(ChatConversation c) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("c1|" + c.getUpdatedAt() + "|" + c.getId()).getBytes(StandardCharsets.UTF_8));
    }
    private static Cursor decode(String cursor) {
        try {
            if (cursor.length() > 200) throw new IllegalArgumentException();
            var fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 3 || !fields[0].equals("c1")) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(fields[1]), UUID.fromString(fields[2]));
        } catch (RuntimeException exception) { throw new BusinessException(INVALID_CURSOR); }
    }
}
