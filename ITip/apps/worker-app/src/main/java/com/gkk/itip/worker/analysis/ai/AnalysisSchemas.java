package com.gkk.itip.worker.analysis.ai;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** 원래 전체 계약에서 단계 Schema를 추출한다. 이 계약이 쓰는 제약을 응답에도 적용한다. */
@Component
public class AnalysisSchemas {
    private final Map<String, Object> full;
    private final JsonMapper mapper;

    public AnalysisSchemas(JsonMapper mapper) throws IOException {
        this.mapper = mapper;
        try (var in = new ClassPathResource("schemas/analysis.spring-v1.json").getInputStream()) {
            full = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
    }

    public Map<String, Object> full() { return copy(full); }
    public Map<String, Object> plan(List<String> ids) {
        return object(Map.of("earlierSections", Map.of("type", "array", "minItems", 0, "maxItems", Math.min(14, ids.size() - 1),
                "items", object(Map.of("title", text(), "endSegmentId", Map.of("type", "string", "enum", ids.size() == 1 ? ids : ids.subList(0, ids.size() - 1))))),
                "lastSectionTitle", text()));
    }

    public Map<String, Object> detail(List<String> ids) {
        var section = map(map(map(full.get("properties")).get("sections")).get("items"));
        var props = map(section.get("properties"));
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("sourceSegmentIds", "summary", "subtopics", "keywords", "detail")) fields.put(key, props.get(key));
        fields.put("mentionedTools", map(map(map(full.get("properties")).get("summary")).get("properties")).get("mentionedTools"));
        return constrain(object(fields), ids, List.of());
    }

    public Map<String, Object> summary(List<String> ids, List<String> keys) {
        return constrain(object(Map.of("summary", map(full.get("properties")).get("summary"), "rootTitle", text())), ids, keys);
    }

    private Map<String, Object> constrain(Map<String, Object> schema, List<String> ids, List<String> keys) {
        var result = copy(schema);
        narrow(result, keys);
        result.put("$defs", Map.of("sourceId", Map.of("type", "string", "enum", ids)));
        return result;
    }

    private void narrow(Object node, List<String> keys) {
        if (node instanceof Map<?, ?> raw) {
            var value = map(raw);
            if ("string".equals(value.get("type")) && !value.containsKey("enum")) value.put("pattern", "\\S");
            if (value.get("properties") instanceof Map<?, ?> props) {
                if (props.containsKey("sourceSegmentIds")) map(props.get("sourceSegmentIds")).put("items", Map.of("$ref", "#/$defs/sourceId"));
                if (props.containsKey("highlightSectionKeys")) map(props.get("highlightSectionKeys")).put("items", Map.of("type", "string", "enum", keys));
            }
            value.values().forEach(child -> narrow(child, keys));
        } else if (node instanceof List<?> list) list.forEach(child -> narrow(child, keys));
    }

    public void validate(JsonNode value, Map<String, Object> schema) { validate(value, schema, schema); }

    private void validate(JsonNode value, Map<String, Object> schema, Map<String, Object> root) {
        if (value == null || value.isNull()) throw invalid();
        if (schema.get("$ref") instanceof String reference) {
            if (!reference.startsWith("#/$defs/")) throw invalid();
            validate(value, map(map(root.get("$defs")).get(reference.substring(8))), root); return;
        }
        switch ((String) schema.get("type")) {
            case "object" -> {
                if (!value.isObject()) throw invalid();
                var props = map(schema.get("properties"));
                // 이 계약의 object 필드는 모두 required이며 추가 필드를 허용하지 않는다.
                if (value.size() != props.size() || !new HashSet<>((List<?>) schema.get("required")).equals(props.keySet())) throw invalid();
                for (var entry : props.entrySet()) validate(value.get(entry.getKey()), map(entry.getValue()), root);
            }
            case "array" -> {
                if (!value.isArray() || value.size() < (int) schema.getOrDefault("minItems", 0)
                        || value.size() > (int) schema.getOrDefault("maxItems", Integer.MAX_VALUE)) throw invalid();
                for (var child : value) validate(child, map(schema.get("items")), root);
            }
            case "string" -> {
                if (!value.isString() || value.asString().isBlank()) throw invalid();
                if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value.asString())) throw invalid();
                if (schema.get("pattern") instanceof String pattern && !Pattern.compile(pattern).matcher(value.asString()).find()) throw invalid();
            }
            default -> throw invalid();
        }
    }

    private Map<String, Object> copy(Map<String, Object> value) { return mapper.convertValue(value, new TypeReference<Map<String, Object>>() {}); }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> object(Map<String, Object> props) {
        return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", false, "required", new ArrayList<>(props.keySet()), "properties", props));
    }
    private static Map<String, Object> text() { return Map.of("type", "string", "pattern", "\\S"); }
    private static AnalysisAiException invalid() { return new AnalysisAiException("AI_INVALID_RESPONSE", false); }
}
