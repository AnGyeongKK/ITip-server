package com.gkk.itip.chat.generation;

import com.gkk.itip.chat.retrieval.ChatContext;
import com.gkk.itip.chat.stream.ChatHistoryEntry;
import com.gkk.itip.chat.stream.ChatLimits;
import com.gkk.itip.common.exception.BusinessException;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.gkk.itip.common.exception.ErrorCode.CHAT_AI_NOT_CONFIGURED;

@Component
public class ChatAnswerGenerator {
    public static final String SCHEMA="""
        {"type":"object","properties":{
          "answer":{"type":"string"},
          "citations":{"type":"array","items":{"type":"object","properties":{
            "contextId":{"type":"string"},"sectionId":{"type":"string"}},
            "required":["contextId","sectionId"],"additionalProperties":false}},
          "evidenceStatus":{"type":"string","enum":["SUPPORTED","INSUFFICIENT_EVIDENCE"]}},
        "required":["answer","citations","evidenceStatus"],"additionalProperties":false}
        """;
    public record ContextInput(String contextId,List<UUID> sectionIds,String title,String text,long startMs,long endMs) {}
    public record Input(String question,List<ContextInput> contexts,List<ChatHistoryEntry> history) {}
    public record Prepared(Prompt prompt,List<ChatContext> contexts) {
        public Prepared { contexts=List.copyOf(contexts); }
    }
    private final ObjectProvider<ChatModel> models;
    private final JsonMapper mapper;
    private final String system;
    private final JTokkitTokenCountEstimator tokens;
    private final int inputBudget;

    public ChatAnswerGenerator(ObjectProvider<ChatModel> models,JsonMapper mapper,
            @Value("classpath:prompts/rag-system.txt") Resource system,
            @Value("${itip.chat.generation.token-encoding:}") String encoding,
            @Value("${itip.chat.generation.input-token-budget:8000}") int inputBudget) throws IOException {
        this.models=models;this.mapper=mapper;this.system=system.getContentAsString(StandardCharsets.UTF_8);
        this.tokens=encoding.isBlank()?null:new JTokkitTokenCountEstimator(EncodingType.valueOf(encoding));
        if(inputBudget<1) throw new IllegalArgumentException("positive chat input token budget required");
        this.inputBudget=inputBudget;
    }
    private ChatModel model() {
        try { var model=models.getIfUnique(); if(model!=null && tokens!=null) return model; }
        catch(org.springframework.beans.BeansException ignored) { }
        throw new BusinessException(CHAT_AI_NOT_CONFIGURED);
    }
    public void requireConfigured() { model(); }
    public Prepared prepare(String question,List<ChatHistoryEntry> history,List<ChatContext> retrieved) {
        requireConfigured();
        var keptHistory=new ArrayList<>(history); var keptContexts=new ArrayList<>(retrieved);
        while(true) {
            var input=new Input(question,contextInputs(keptContexts),List.copyOf(keptHistory));
            String data=mapper.writeValueAsString(input);
            // 역할 봉투·공급자 schema 처리 차이에 여유128토큰을 남긴 추정치다.
            int count=tokens.estimate(system)+tokens.estimate(data)+tokens.estimate(SCHEMA)+128;
            if(count<=inputBudget) return new Prepared(new Prompt(new SystemMessage(system),new UserMessage(data)),keptContexts);
            if(!keptHistory.isEmpty()) { keptHistory.subList(0,Math.min(2,keptHistory.size())).clear(); continue; }
            if(!keptContexts.isEmpty()) { keptContexts.removeLast(); continue; }
            throw new ChatAnswerException("CHAT_PROMPT_TOO_LARGE",false);
        }
    }
    private List<ContextInput> contextInputs(List<ChatContext> contexts) {
        var result=new ArrayList<ContextInput>();
        for(var context:contexts) result.add(new ContextInput("C"+(result.size()+1),context.sectionIds(),context.title(),
            context.text(),context.startMs(),context.endMs()));
        return List.copyOf(result);
    }
    public Flux<ChatResponse> stream(Prepared prepared) {
        return model().stream(prepared.prompt()).timeout(ChatLimits.IDLE_TIMEOUT).take(ChatLimits.STREAM_DURATION)
            .takeUntil(response->response.getResults().stream().anyMatch(g->
                g.getMetadata().getFinishReason()!=null && !g.getMetadata().getFinishReason().isBlank()
                || g.getOutput().getMetadata().get("refusal") instanceof String refusal && !refusal.isBlank()));
    }
}
