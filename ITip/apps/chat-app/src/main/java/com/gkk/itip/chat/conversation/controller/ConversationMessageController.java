package com.gkk.itip.chat.conversation.controller;

import com.gkk.itip.chat.conversation.dto.MessageView;
import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public class ConversationMessageController {
    private final MessageQueryService messages;
    private final DemoUserProvider user;
    public ConversationMessageController(MessageQueryService messages, DemoUserProvider user) { this.messages = messages; this.user = user; }

    @GetMapping
    public ApiResponse<MessageView.Page> list(@PathVariable UUID conversationId,
            @RequestParam(required = false) UUID clientRequestId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return ApiResponse.success(messages.list(user.ownerId(), conversationId, clientRequestId, cursor, limit));
    }

    @GetMapping("/{messageId}")
    public ApiResponse<MessageView> get(@PathVariable UUID conversationId, @PathVariable UUID messageId) {
        return ApiResponse.success(messages.get(user.ownerId(), conversationId, messageId));
    }
}
