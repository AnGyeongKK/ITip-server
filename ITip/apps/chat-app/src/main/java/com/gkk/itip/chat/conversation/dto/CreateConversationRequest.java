package com.gkk.itip.chat.conversation.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
public record CreateConversationRequest(@NotNull @Valid ChatScope scope) {}
