package com.gkk.itip.worker.analysis.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** 직접 작성한 고정 응답이다. 모델 품질·실제 외부 통신의 증거가 아니다. */
public final class SplitAnalysisFixtures {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private SplitAnalysisFixtures() {}
    public static ChatResponse reply(Prompt prompt) { return reply(MAPPER.writeValueAsString(payload(input(prompt))), "STOP", 100); }
    public static JsonNode input(Prompt prompt) { return MAPPER.readTree(prompt.getInstructions().getLast().getText()); }
    public static Map<String, Object> payload(JsonNode input) {
        if (input.has("section")) {
            String id = input.get("groups").get(0).get("groupId").asString();
            return Map.of("sourceSegmentIds", List.of(id), "summary", "원문 검색", "subtopics", List.of("근거"), "keywords", List.of("검색"),
                "mentionedTools", List.of(), "detail", Map.of("takeaway", "근거 확인", "explanation", "원문을 검색합니다.",
                    "applicationTips", List.of(), "recommendedTools", List.of(), "usageKeywords", List.of(),
                    "examples", List.of(Map.of("text", "원문 근거를 확인합니다.", "origin", "SOURCE", "sourceSegmentIds", List.of(id)))));
        }
        if (input.has("evidence")) return Map.of("rootTitle", "검색", "summary", Map.of("oneLine", "근거 검색", "keyPoints", List.of("검색"),
            "practicalPoints", List.of(), "mentionedTools", List.of(), "keywords", List.of(),
            "highlightSectionKeys", List.of(input.get("sections").get(0).get("sectionKey").asString())));
        var groups = input.get("groups");
        return Map.of("earlierSections", groups.size() == 1 ? List.of() : List.of(Map.of("title", "앞 주제", "endSegmentId", groups.get(0).get("groupId").asString())),
            "lastSectionTitle", "근거");
    }
    public static ChatResponse replyWithInvalidDetail(Prompt prompt) {
        var input = input(prompt);
        String json = MAPPER.writeValueAsString(payload(input));
        if (input.has("section")) json = json.replace(input.get("groups").get(0).get("groupId").asString(), "g999999");
        return reply(json, "STOP", 100);
    }
    public static ChatResponse reply(String text, String finish, int tokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(finish).build())),
            ChatResponseMetadata.builder().model("gpt-6-luna").usage(new DefaultUsage(100, tokens)).build());
    }
}
