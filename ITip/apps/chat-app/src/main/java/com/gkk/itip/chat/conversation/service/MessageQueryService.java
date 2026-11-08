package com.gkk.itip.chat.conversation.service;

import com.gkk.itip.chat.conversation.dto.MessageView;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.AnalysisRecord;
import com.gkk.itip.domain.chat.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static com.gkk.itip.common.exception.ErrorCode.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MessageQueryService {
    private final ChatConversationRepository rooms;
    private final ChatMessageRepository messages;
    private final ChatMessageSourceRepository sources;
    private final AnalysisRecordRepository records;
    public MessageQueryService(ChatConversationRepository rooms, ChatMessageRepository messages,
                               ChatMessageSourceRepository sources, AnalysisRecordRepository records) {
        this.rooms = rooms; this.messages = messages; this.sources = sources; this.records = records;
    }

    public MessageView.Page list(String owner, UUID room, UUID clientRequestId, String cursor, int limit) {
        owned(owner, room);
        if (clientRequestId != null && cursor != null) throw new BusinessException(INVALID_REQUEST);
        if (clientRequestId != null) return new MessageView.Page(views(owner,
                messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room, clientRequestId)), null, false);
        var found = messages.findByConversationIdAndPositionLessThanOrderByPositionDesc(room, before(room, cursor), PageRequest.of(0, limit + 1));
        boolean more = found.size() > limit;
        var page = found.subList(0, Math.min(limit, found.size()));
        String next = more ? encode(room, page.getLast().getPosition()) : null;
        return new MessageView.Page(views(owner, page.reversed()), next, more);
    }

    public MessageView get(String owner, UUID room, UUID message) {
        owned(owner, room);
        var row = messages.findByIdAndConversationId(message, room).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        return views(owner, List.of(row)).getFirst();
    }

    private void owned(String owner, UUID room) {
        rooms.findByIdAndOwnerId(room, owner).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
    }
    private List<MessageView> views(String owner, List<ChatMessage> rows) {
        if (rows.isEmpty()) return List.of();
        var references = sources.findAllByMessageIdInOrderBySourceIdAsc(rows.stream().map(ChatMessage::getId).toList());
        var ids = references.stream().map(ChatMessageSource::getRecordId).distinct().toList();
        var available = ids.isEmpty() ? Map.<UUID, AnalysisRecord>of()
                : records.findAllByIdInAndOwnerId(ids, owner).stream().collect(Collectors.toMap(r -> r.getId(), r -> r));
        var byMessage = references.stream().collect(Collectors.groupingBy(ChatMessageSource::getMessageId));
        return rows.stream().<MessageView>map(m -> {
            if (m.getRole() == MessageRole.USER) return new MessageView.User(m.getId(), m.getRole(), m.getClientRequestId(), m.getContent(), m.getCreatedAt());
            var mapped = byMessage.getOrDefault(m.getId(), List.of()).stream().map(s -> {
                var record = available.get(s.getRecordId());
                boolean exists = record != null && record.getAnalysisId().equals(s.getAnalysisId()) && record.getVideoId().equals(s.getVideoId());
                return new MessageView.Source(s.getSourceId(), s.getRecordId(), s.getAnalysisId(), s.getSectionId(),
                        s.getTitle(), s.getVideoId(), s.getStartMs(), s.getEndMs(), exists ? "AVAILABLE" : "DELETED", s.getAnalyzedAt());
            }).toList();
            boolean active = m.getStatus().active();
            var error = m.getErrorCode() == null ? null : new MessageView.Failure(m.getErrorCode(), m.getErrorMessage(), Boolean.TRUE.equals(m.getRetryable()));
            return new MessageView.Assistant(m.getId(), m.getRole(), m.getReplyToMessageId(), m.getClientRequestId(),
                    m.getStatus(), m.getContent(), mapped, m.getFinishReason(), m.getCreatedAt(), m.getCompletedAt(),
                    error, Boolean.TRUE.equals(m.getRetryable()), active ? 3000L : null, m.getScopeRecordCount(), m.getUsedRecordCount());
        }).toList();
    }

    private static String encode(UUID room, long position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("m1:" + room + ":" + position).getBytes(StandardCharsets.UTF_8));
    }
    private static long before(UUID room, String cursor) {
        if (cursor == null) return Long.MAX_VALUE;
        try {
            if (cursor.length() > 200) throw new IllegalArgumentException();
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3 || !parts[0].equals("m1") || !parts[1].equals(room.toString())) throw new IllegalArgumentException();
            long position = Long.parseLong(parts[2]);
            if (position <= 0) throw new IllegalArgumentException();
            return position;
        } catch (IllegalArgumentException exception) { throw new BusinessException(INVALID_CURSOR); }
    }
}
