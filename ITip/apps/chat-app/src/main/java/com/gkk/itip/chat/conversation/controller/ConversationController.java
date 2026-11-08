package com.gkk.itip.chat.conversation.controller;

import com.gkk.itip.chat.conversation.dto.ConversationCreated;
import com.gkk.itip.chat.conversation.dto.CreateConversationRequest;
import com.gkk.itip.chat.conversation.service.ConversationService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService rooms;
    private final DemoUserProvider user;
    public ConversationController(ConversationService rooms, DemoUserProvider user) { this.rooms = rooms; this.user = user; }
    @PostMapping
    public ResponseEntity<ApiResponse<ConversationCreated>> create(@RequestHeader("Idempotency-Key") UUID key,
                                                                   @Valid @RequestBody CreateConversationRequest request) {
        var created = rooms.create(user.ownerId(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + created.conversationId())).body(ApiResponse.success(created));
    }
}
