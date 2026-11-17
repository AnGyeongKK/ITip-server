package com.gkk.itip.worker.analysis.ai;

import com.gkk.itip.worker.analysis.caption.AnalysisCaptionGroups;
import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import com.gkk.itip.worker.analysis.output.AnalysisOutput;
import com.gkk.itip.worker.config.AnalysisGenerationProperties;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** 외부 호출만 조율하며 최종 결과 전에는 DB draft·완료를 만들지 않는다. */
@Component
public class SplitAnalysisGenerator {
    private static final Logger log = LoggerFactory.getLogger(SplitAnalysisGenerator.class);
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private final ObjectProvider<ChatModel> models;
    private final JsonMapper mapper;
    private final AnalysisSchemas schemas;
    private final AnalysisCaptionGroups grouping;
    private final AnalysisGenerationProperties properties;
    private final JTokkitTokenCountEstimator tokens;
    private final Map<String, String> instructions = new HashMap<>();

    public SplitAnalysisGenerator(ObjectProvider<ChatModel> models, JsonMapper mapper, AnalysisSchemas schemas,
            AnalysisCaptionGroups grouping, AnalysisGenerationProperties properties) throws IOException {
        this.models = models; this.schemas = schemas; this.grouping = grouping; this.properties = properties;
        this.mapper = mapper.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.tokens = properties.tokenEncoding().isBlank() ? null : new JTokkitTokenCountEstimator(EncodingType.valueOf(properties.tokenEncoding()));
        for (String stage : List.of("plan", "detail", "summary")) instructions.put(stage,
                new ClassPathResource("prompts/analysis-split/" + stage + ".system.md").getContentAsString(StandardCharsets.UTF_8));
    }

    public void checkConfigured() { model(); }

    private ChatModel model() {
        if (tokens == null || properties.model().isBlank()) throw new AnalysisAiException("AI_NOT_CONFIGURED", false);
        try { var model = models.getIfUnique(); if (model != null) return model; }
        catch (org.springframework.beans.BeansException ignored) { }
        throw new AnalysisAiException("AI_NOT_CONFIGURED", false);
    }

    public AnalysisOutput generate(CaptionDocument captions, long durationMs, String title) {
        var model = model();
        var prepared = grouping.prepare(captions, durationMs, title);
        var ids = prepared.groups().stream().map(AnalysisCaptionGroups.Group::groupId).toList();
        var plan = call(model, "plan", prepared.input(), schemas.plan(ids), properties.planOutputTokens());
        var fixed = freeze(plan.output(), ids);
        int used = plan.completionTokens();
        var sections = new ArrayList<Map<String, Object>>();
        var mentioned = new ArrayList<JsonNode>();
        try (var executor = Executors.newFixedThreadPool(properties.detailConcurrency())) {
            for (int offset = 0; offset < fixed.size(); offset += properties.detailConcurrency()) {
                var batch = fixed.subList(offset, Math.min(fixed.size(), offset + properties.detailConcurrency()));
                int available = properties.totalOutputTokens() - used - properties.summaryOutputTokens();
                int cap = Math.min(properties.detailOutputTokens(), available / batch.size());
                if (cap < 1) throw invalid();
                var prompts = new ArrayList<Prompt>();
                for (var section : batch) {
                    var selected = select(prepared, section, ids);
                    var input = metadata(prepared.input()); input.put("section", section); input.put("groups", selected);
                    var allowed = selected.stream().map(g -> (String) g.get("groupId")).toList();
                    prompts.add(prompt("detail", input, schemas.detail(allowed), cap));
                }
                var context = MDC.getCopyOfContextMap();
                var futures = prompts.stream().map(p -> executor.submit(() -> {
                    var previous = MDC.getCopyOfContextMap();
                    try { if (context != null) MDC.setContextMap(context); else MDC.clear(); return call(model, "detail", p, cap); }
                    finally { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
                })).toList();
                var results = await(futures);
                for (int i = 0; i < results.size(); i++) {
                    var result = results.get(i); used += result.completionTokens();
                    var value = result.output(); schemas.validate(value, schema(prompts.get(i)));
                    var section = new LinkedHashMap<>(batch.get(i));
                    for (String key : List.of("sourceSegmentIds", "summary", "subtopics", "keywords", "detail")) section.put(key, value.get(key));
                    sections.add(section); mentioned.add(value.get("mentionedTools"));
                }
            }
        }
        var refs = new HashSet<String>(); collectReferences(mapper.valueToTree(sections), refs); collectReferences(mapper.valueToTree(mentioned), refs);
        var evidence = prepared.groups().stream().filter(g -> refs.contains(g.groupId()) || DIGIT.matcher(g.text()).find())
                .map(g -> Map.of("groupId", g.groupId(), "text", g.text())).toList();
        var input = metadata(prepared.input()); input.put("sections", sections); input.put("sectionMentionedTools", mentioned); input.put("evidence", evidence);
        var summary = call(model, "summary", input, schemas.summary(evidence.stream().map(g -> g.get("groupId")).toList(),
                sections.stream().map(s -> (String) s.get("sectionKey")).toList()), Math.min(properties.summaryOutputTokens(), properties.totalOutputTokens() - used));
        var full = new LinkedHashMap<String, Object>(); full.put("schemaVersion", "1.0"); full.put("sections", sections);
        full.put("summary", summary.output().get("summary"));
        full.put("mindmap", Map.of("rootTitle", summary.output().get("rootTitle"), "sectionKeys", sections.stream().map(s -> s.get("sectionKey")).toList()));
        var tree = mapper.valueToTree(full); schemas.validate(tree, schemas.full());
        return grouping.expand(mapper.treeToValue(tree, AnalysisOutput.class), prepared);
    }

    private StageResult call(ChatModel model, String stage, Map<String, Object> input, Map<String, Object> schema, int cap) {
        var result = call(model, stage, prompt(stage, input, schema, cap), cap);
        schemas.validate(result.output(), schema); return result;
    }

    private Prompt prompt(String stage, Map<String, Object> input, Map<String, Object> schema, int cap) {
        if (cap < 1) throw invalid();
        String system = instructions.get(stage), json = mapper.writeValueAsString(input), format = mapper.writeValueAsString(schema);
        long count = (long) tokens.estimate(system) + tokens.estimate(json) + tokens.estimate(format) + 128;
        if (count > properties.inputTokenBudget()) throw new AnalysisAiException("AI_INVALID_INPUT", false);
        var options = OpenAiChatOptions.builder().model(properties.model()).reasoningEffort("low").maxRetries(0).store(false)
                .maxCompletionTokens(cap).responseFormat(OpenAiChatModel.ResponseFormat.builder().jsonSchema(format).strict(true).build()).build();
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(json)), options);
    }

    private StageResult call(ChatModel model, String stage, Prompt prompt, int cap) {
        org.springframework.ai.chat.model.ChatResponse response;
        try { response = model.call(prompt); }
        catch (RuntimeException exception) { throw SpringAiAnalysisClient.providerFailure(exception); }
        if (response == null || response.getResults().size() != 1 || response.getResult().getOutput() == null) throw invalid();
        var generation = response.getResult();
        String finish = generation.getMetadata().getFinishReason();
        Object refusal = generation.getOutput().getMetadata().get("refusal");
        if (refusal instanceof String text && !text.isBlank() || "content_filter".equalsIgnoreCase(finish)) {
            log.warn("ANALYSIS_MODEL_INCOMPLETE stage={} finishReason=REFUSED", stage);
            throw new AnalysisAiException("AI_REQUEST_REJECTED", false);
        }
        if (!"stop".equalsIgnoreCase(finish)) {
            log.warn("ANALYSIS_MODEL_INCOMPLETE stage={} finishReason={}", stage, "length".equalsIgnoreCase(finish) ? "LENGTH" : "OTHER");
            throw invalid();
        }
        var usage = response.getMetadata().getUsage();
        if (usage == null || usage.getCompletionTokens() == null || usage.getCompletionTokens() < 1 || usage.getCompletionTokens() > cap) throw invalid();
        String text = generation.getOutput().getText();
        if (text == null || text.isBlank()) throw invalid();
        try { return new StageResult(mapper.readTree(text), usage.getCompletionTokens()); }
        catch (RuntimeException exception) { throw invalid(); }
    }

    private List<StageResult> await(List<Future<StageResult>> futures) {
        var results = new ArrayList<StageResult>(); RuntimeException failure = null;
        for (var future : futures) {
            try { results.add(future.get()); }
            catch (InterruptedException exception) {
                futures.forEach(f -> f.cancel(true)); Thread.currentThread().interrupt();
                throw new AnalysisAiException("AI_UNAVAILABLE", true);
            } catch (ExecutionException exception) {
                if (failure == null) failure = exception.getCause() instanceof AnalysisAiException ai ? ai : invalid();
            }
        }
        if (failure != null) throw failure; return results;
    }

    private List<Map<String, Object>> freeze(JsonNode plan, List<String> ids) {
        var result = new ArrayList<Map<String, Object>>(); int previous = -1;
        for (var item : plan.get("earlierSections")) {
            int end = ids.indexOf(item.get("endSegmentId").asString());
            if (end <= previous || end >= ids.size() - 1) throw invalid();
            result.add(section(result.size(), item.get("title").asString(), ids.get(previous + 1), ids.get(end))); previous = end;
        }
        result.add(section(result.size(), plan.get("lastSectionTitle").asString(), ids.get(previous + 1), ids.getLast()));
        return result;
    }

    private Map<String, Object> section(int index, String title, String start, String end) {
        return Map.of("sectionKey", "section-%02d".formatted(index + 1), "title", title, "startSegmentId", start, "endSegmentId", end);
    }
    private List<Map<String, Object>> select(AnalysisCaptionGroups.Prepared prepared, Map<String, Object> section, List<String> ids) {
        return prepared.groups().subList(ids.indexOf(section.get("startSegmentId")), ids.indexOf(section.get("endSegmentId")) + 1)
                .stream().map(g -> Map.<String, Object>of("groupId", g.groupId(), "text", g.text())).toList();
    }
    private LinkedHashMap<String, Object> metadata(Map<String, Object> input) { var result = new LinkedHashMap<>(input); result.remove("groups"); return result; }
    private void collectReferences(JsonNode node, Set<String> refs) {
        if (node.isObject()) {
            var sources = node.get("sourceSegmentIds"); if (sources != null) sources.forEach(id -> refs.add(id.asString()));
        }
        if ((node.isArray() || node.isObject())) node.forEach(child -> collectReferences(child, refs));
    }
    private Map<String, Object> schema(Prompt prompt) {
        var options = (OpenAiChatOptions) prompt.getOptions();
        return mapper.readValue(options.getResponseFormat().getJsonSchema(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
    private static AnalysisAiException invalid() { return new AnalysisAiException("AI_INVALID_RESPONSE", false); }
    private record StageResult(JsonNode output, int completionTokens) {}
}
