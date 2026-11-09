package com.gkk.itip.chat.retrieval;

import com.gkk.itip.chat.stream.ChatCitation;
import java.util.List;
import java.util.UUID;

/** Python에 전달하는 검색 원문. 제목·시간·식별자는 DB 값이며 모델이 생성하지 않는다. */
public record ChatContext(UUID recordId, UUID analysisId, UUID chunkId, List<UUID> sectionIds,
                          String videoId, String title, String text, long startMs, long endMs) {
    public ChatContext { sectionIds = List.copyOf(sectionIds); }
    public List<ChatCitation> citations() {
        return sectionIds.stream().map(section -> new ChatCitation(recordId, chunkId, section)).toList();
    }
}
