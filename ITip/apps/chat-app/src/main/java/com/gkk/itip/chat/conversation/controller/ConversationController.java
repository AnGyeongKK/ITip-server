package com.gkk.itip.chat.conversation.controller;

import com.gkk.itip.chat.conversation.dto.ConversationCreated;
import com.gkk.itip.chat.conversation.dto.CreateConversationRequest;
import com.gkk.itip.chat.conversation.dto.ConversationView;
import com.gkk.itip.chat.conversation.service.ConversationService;
import com.gkk.itip.chat.conversation.service.ConversationQueryService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService rooms;
    private final ConversationQueryService queries;
    private final DemoUserProvider user;
    public ConversationController(ConversationService rooms, ConversationQueryService queries, DemoUserProvider user) {
        this.rooms = rooms; this.queries = queries; this.user = user;
    }
    @PostMapping
    public ResponseEntity<ApiResponse<ConversationCreated>> create(@RequestHeader("Idempotency-Key") UUID key,
                                                                   @Valid @RequestBody CreateConversationRequest request) {
        var created = rooms.create(user.ownerId(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + created.conversationId())).body(ApiResponse.success(created));
    }
    @GetMapping
    public ApiResponse<ConversationView.Page> list(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return ApiResponse.success(queries.list(user.ownerId(), cursor, limit));
    }
    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationView.Detail> get(@PathVariable UUID conversationId) {
        return ApiResponse.success(queries.get(user.ownerId(), conversationId));
    }
    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(@PathVariable UUID conversationId) {
        rooms.delete(user.ownerId(), conversationId);
        return ApiResponse.success("요청을 처리했어요.");
    }
}
