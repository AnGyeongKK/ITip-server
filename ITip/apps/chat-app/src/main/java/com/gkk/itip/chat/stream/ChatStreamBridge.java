package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.conversation.dto.MessageView;
import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.chat.infrastructure.PythonChatClient;
import com.gkk.itip.domain.chat.FinishReason;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.gkk.itip.domain.chat.MessageStatus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class ChatStreamBridge {
    private static final Logger LOG=LoggerFactory.getLogger(ChatStreamBridge.class);
    private final PythonChatClient python;
    private final ChatGenerationStore store;
    private final MessageQueryService messages;
    private final JsonMapper mapper;
    public ChatStreamBridge(PythonChatClient python,ChatGenerationStore store,MessageQueryService messages,JsonMapper mapper) {
        this.python=python;this.store=store;this.messages=messages;this.mapper=mapper;
    }
    public void write(GenerationTicket ticket,String question,OutputStream out) {
        var text=new StringBuilder();
        boolean committed=false;
        try(var events=python.stream(ticket,question).toStream(1)) {
            event(out,"meta",Map.of("conversationId",ticket.conversationId(),"userMessageId",ticket.userMessageId(),
                "assistantMessageId",ticket.assistantMessageId(),"clientRequestId",ticket.clientRequestId(),"scopeRecordCount",ticket.scopeRecordCount()));
            int sequence=0; long checkpoint=0;
            List<ChatCitation> sources=null;
            var iterator=events.iterator();
            while(iterator.hasNext()) {
                var e=iterator.next();
                if(e.data()==null && e.comment()!=null) {out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));out.flush();continue;}
                JsonNode data=e.data();
                if(data==null || e.event()==null) throw new ChatProtocolException();
                switch(e.event()) {
                    case "delta" -> {
                        if(sources!=null || !data.path("text").isString()) throw new ChatProtocolException();
                        String delta=data.path("text").asString();
                        if(text.length()+delta.length()>ChatLimits.MAX_ANSWER_CHARS) throw new ChatProtocolException();
                        text.append(delta);
                        if(System.nanoTime()-checkpoint>250_000_000L || checkpoint==0) {
                            if(!store.checkpoint(ticket,text.toString())) throw new ChatProtocolException();
                            checkpoint=System.nanoTime();
                        }
                        event(out,"delta",Map.of("assistantMessageId",ticket.assistantMessageId(),"sequence",++sequence,"text",delta));
                    }
                    case "sources" -> {
                        if(sources!=null || !data.path("items").isArray() || data.path("items").size()>ChatLimits.MAX_SOURCES) throw new ChatProtocolException();
                        sources=new ArrayList<>();
                        for(var s:data.path("items")) sources.add(new ChatCitation(UUID.fromString(s.path("recordId").asString()),
                            UUID.fromString(s.path("chunkId").asString()),UUID.fromString(s.path("sectionId").asString())));
                    }
                    case "done" -> {
                        if(sources==null) throw new ChatProtocolException();
                        var reason=FinishReason.valueOf(data.path("finishReason").asString());
                        if(!store.complete(ticket,text.toString(),reason,sources)) throw new ChatProtocolException();
                        committed=true;
                        var answer=(MessageView.Assistant)messages.get(ticket.ownerId(),ticket.conversationId(),ticket.assistantMessageId());
                        event(out,"sources",Map.of("assistantMessageId",ticket.assistantMessageId(),"sources",answer.sources(),"usedRecordCount",answer.usedRecordCount()));
                        event(out,"done",Map.of("assistantMessageId",ticket.assistantMessageId(),"status",answer.status(),"finishReason",answer.finishReason(),"usedRecordCount",answer.usedRecordCount()));
                        return;
                    }
                    case "error" -> throw new IOException("upstream error");
                    default -> throw new ChatProtocolException();
                }
            }
            throw new EOFException("AI stream ended without done");
        } catch(Exception failure) {
            if(committed) return;
            boolean invalid=failure instanceof ChatProtocolException || failure instanceof IllegalArgumentException
                || failure instanceof DecodingException || failure instanceof DataBufferLimitException;
            boolean retryable=!invalid && (!(failure instanceof WebClientResponseException http) || http.getStatusCode().value()==429 || http.getStatusCode().is5xxServerError());
            String code=invalid?"CHAT_INVALID_AI_RESPONSE":"CHAT_STREAM_FAILED";
            MessageStatus status;
            try {status=store.fail(ticket,text.toString(),code,retryable);}
            catch(RuntimeException persistenceFailure) {
                LOG.warn("chat failure state could not be saved messageId={} failureType={}",
                    ticket.assistantMessageId(),persistenceFailure.getClass().getSimpleName());
                return;
            }
            if(status!=null) try {
                event(out,"error",Map.of("assistantMessageId",ticket.assistantMessageId(),"status",status,"code",code,
                    "message","답변 생성이 중단됐어요.","retryable",retryable));
            } catch(IOException disconnected) { }
        }
    }
    private void event(OutputStream out,String name,Object data) throws IOException {
        out.write(("event: "+name+"\ndata: "+mapper.writeValueAsString(data)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
    }
}
