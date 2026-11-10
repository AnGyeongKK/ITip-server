package com.gkk.itip.chat;

import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** 공식 SDK가 읽는 로컬 HTTP 응답. 실제 API 키나 모델 응답이 아니다. */
final class ChatModelFixture {
    private static final JsonMapper MAPPER=JsonMapper.builder().build();
    static void properties(DynamicPropertyRegistry r,int port) {
        r.add("itip.chat.generation.enabled",()->true);
        r.add("itip.chat.generation.api-key",()->"fixture-only-not-a-secret");
        r.add("itip.chat.generation.base-url",()->"http://127.0.0.1:"+port+"/v1");
        r.add("itip.chat.generation.model",()->"fixture-answer");
        r.add("itip.chat.generation.token-encoding",()->"O200K_BASE");
        r.add("itip.chat.generation.context-tokens",()->32768);
        r.add("itip.chat.generation.input-token-budget",()->8000);
        r.add("itip.chat.generation.max-output-tokens",()->2048);
    }
    static String chunk(String content,String finish) {
        var choice=new LinkedHashMap<String,Object>();choice.put("index",0);choice.put("delta",Map.of("content",content));choice.put("finish_reason",finish);
        return "data: "+MAPPER.writeValueAsString(Map.of("id","fixture","object","chat.completion.chunk","created",1,
            "model","fixture-answer","choices",List.of(choice)))+"\n\n";
    }
    static String raw(String json,String finish) { return chunk(json,finish)+"data: [DONE]\n\n"; }
    static String answer(String text,String contextId,UUID sectionId) {
        var output=new LinkedHashMap<String,Object>();output.put("answer",text);
        output.put("citations",contextId==null?List.of():List.of(Map.of("contextId",contextId,"sectionId",sectionId)));
        output.put("evidenceStatus",contextId==null?"INSUFFICIENT_EVIDENCE":"SUPPORTED");
        return raw(MAPPER.writeValueAsString(output),"stop");
    }
}
