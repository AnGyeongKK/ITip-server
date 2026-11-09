package com.gkk.itip.chat.conversation.dto;

import com.gkk.itip.domain.chat.FinishReason;
import com.gkk.itip.domain.chat.MessageRole;
import com.gkk.itip.domain.chat.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public sealed interface MessageView permits MessageView.User, MessageView.Assistant {
    record User(UUID messageId, MessageRole role, UUID clientRequestId, String content, Instant createdAt) implements MessageView {}
    record Assistant(UUID messageId, MessageRole role, UUID replyToMessageId, UUID clientRequestId,
                     MessageStatus status, String content, List<Source> sources, FinishReason finishReason,
                     Instant createdAt, Instant completedAt, Failure error, boolean retryable, Long nextPollAfterMs,
                     int scopeRecordCount, int usedRecordCount) implements MessageView {}
    record Source(String sourceId, UUID recordId, UUID analysisId, UUID sectionId, String title, String videoId,
                  long startMs, long endMs, String availability, Instant analyzedAt) {}
    record Failure(String code, String message, boolean retryable) {}
    record Page(List<MessageView> items, String nextCursor, boolean hasNext) {}
}
