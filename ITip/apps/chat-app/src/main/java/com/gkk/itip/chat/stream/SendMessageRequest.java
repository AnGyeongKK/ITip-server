package com.gkk.itip.chat.stream;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SendMessageRequest(@NotNull UUID clientRequestId, @NotNull String content, UUID retryOfMessageId) {}
