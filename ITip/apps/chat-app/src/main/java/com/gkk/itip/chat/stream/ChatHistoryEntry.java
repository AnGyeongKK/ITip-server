package com.gkk.itip.chat.stream;
import com.gkk.itip.domain.chat.MessageRole;
public record ChatHistoryEntry(MessageRole role,String content) {}
