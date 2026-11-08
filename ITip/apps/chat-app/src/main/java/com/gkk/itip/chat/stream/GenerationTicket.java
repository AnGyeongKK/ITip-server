package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef;
import java.util.List;
import java.util.UUID;

public record GenerationTicket(String ownerId, UUID conversationId, UUID userMessageId, UUID assistantMessageId,
                               UUID clientRequestId, UUID token, List<RecordRef> records) {
    public GenerationTicket { records = List.copyOf(records); }
    public int scopeRecordCount() { return records.size(); }
}
