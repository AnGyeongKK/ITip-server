package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.generation.ChatAnswerGenerator;
import com.gkk.itip.chat.retrieval.QuestionEmbeddingClient;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import static com.gkk.itip.common.exception.ErrorCode.CHAT_CAPACITY_EXCEEDED;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public class ChatStreamController {
    private final ChatGenerationStore store;
    private final ChatStreamBridge bridge;
    private final ChatAnswerGenerator generator;
    private final DemoUserProvider user;
    private final QuestionEmbeddingClient embeddings;
    private final Semaphore capacity=new Semaphore(ChatLimits.CONCURRENT_STREAMS);
    public ChatStreamController(ChatGenerationStore store,ChatStreamBridge bridge,ChatAnswerGenerator generator,DemoUserProvider user,QuestionEmbeddingClient embeddings) {
        this.store=store;this.bridge=bridge;this.generator=generator;this.user=user;this.embeddings=embeddings;
    }
    @PostMapping(produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> send(@PathVariable UUID conversationId,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody SendMessageRequest request,HttpServletResponse response) {
        generator.requireConfigured();
        embeddings.requireConfigured();
        if(!capacity.tryAcquire()) throw new BusinessException(CHAT_CAPACITY_EXCEEDED);
        GenerationTicket ticket;
        try {ticket=store.begin(user.ownerId(),conversationId,key,request);}
        catch(RuntimeException failure) {capacity.release();throw failure;}
        // Spring 7.0.6+ wraps the callback stream with nonFlushing; SSE needs the servlet stream's flush.
        StreamingResponseBody body=ignored->{try {bridge.write(ticket,request.content(),response.getOutputStream());}finally {capacity.release();}};
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noCache())
            .header("X-Accel-Buffering","no").body(body);
    }
}
