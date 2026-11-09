package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.conversation.dto.MessageView;
import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.chat.generation.*;
import com.gkk.itip.chat.retrieval.*;
import com.gkk.itip.domain.chat.MessageStatus;
import com.gkk.itip.ai.AiHttpRetryPolicy;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIIoException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Component
public class ChatStreamBridge {
    private static final Logger LOG=LoggerFactory.getLogger(ChatStreamBridge.class);
    private final ChatAnswerGenerator generator;
    private final ChatGenerationStore store;
    private final MessageQueryService messages;
    private final JsonMapper mapper;
    private final QuestionEmbeddingClient embeddings;
    private final ChatContextQuery contexts;
    public ChatStreamBridge(ChatAnswerGenerator generator,ChatGenerationStore store,MessageQueryService messages,JsonMapper mapper,
            QuestionEmbeddingClient embeddings,ChatContextQuery contexts) {
        this.generator=generator;this.store=store;this.messages=messages;this.mapper=mapper;
        this.embeddings=embeddings;this.contexts=contexts;
    }
    public void write(GenerationTicket ticket,String question,OutputStream out) {
        var text=new StringBuilder();boolean committed=false;
        try {
            event(out,"meta",Map.of("conversationId",ticket.conversationId(),"userMessageId",ticket.userMessageId(),
                "assistantMessageId",ticket.assistantMessageId(),"clientRequestId",ticket.clientRequestId(),"scopeRecordCount",ticket.scopeRecordCount()));
            var vector=embeddings.embed(question);
            var fresh=store.refresh(ticket);
            var retrieved=contexts.search(fresh.ownerId(),fresh.records(),vector);
            var prepared=generator.prepare(question,fresh.history(),retrieved);
            var candidates=prepared.contexts().stream().flatMap(context->context.citations().stream()).toList();
            var decoder=new AnswerJsonStream();
            int sequence=0;long checkpoint=0;
            try(var responses=generator.stream(prepared).toStream(1)) {
                var iterator=responses.iterator();
                while(iterator.hasNext()) {
                    String delta=decoder.accept(iterator.next());
                    if(delta.isEmpty()) continue;
                    text.append(delta);
                    if(checkpoint==0 || System.nanoTime()-checkpoint>250_000_000L) {
                        if(!store.checkpoint(ticket,text.toString())) throw new ChatProtocolException();
                        checkpoint=System.nanoTime();
                    }
                    event(out,"delta",Map.of("assistantMessageId",ticket.assistantMessageId(),"sequence",++sequence,"text",delta));
                }
            }
            var result=decoder.finish(prepared);
            if(!result.answer().contentEquals(text)) throw new ChatProtocolException();
            if(!store.complete(ticket,result.answer(),result.reason(),result.citations(),candidates)) throw new ChatProtocolException();
            committed=true;
            var answer=(MessageView.Assistant)messages.get(ticket.ownerId(),ticket.conversationId(),ticket.assistantMessageId());
            event(out,"sources",Map.of("assistantMessageId",ticket.assistantMessageId(),"sources",answer.sources(),"usedRecordCount",answer.usedRecordCount()));
            event(out,"done",Map.of("assistantMessageId",ticket.assistantMessageId(),"status",answer.status(),"finishReason",answer.finishReason(),"usedRecordCount",answer.usedRecordCount()));
        } catch(Exception failure) {
            if(committed) return;
            String code=failure instanceof ChatRetrievalException?"CHAT_RETRIEVAL_FAILED":
                failure instanceof ChatAnswerException answer?answer.code():
                failure instanceof ChatProtocolException || failure instanceof IllegalArgumentException?"CHAT_INVALID_AI_RESPONSE":"CHAT_STREAM_FAILED";
            boolean retryable=retryable(failure);
            MessageStatus status;
            try {status=store.fail(ticket,text.toString(),code,retryable);}
            catch(RuntimeException persistenceFailure) {
                LOG.warn("chat failure state could not be saved messageId={} failureType={}",
                    ticket.assistantMessageId(),persistenceFailure.getClass().getSimpleName());return;
            }
            if(status!=null) try {
                event(out,"error",Map.of("assistantMessageId",ticket.assistantMessageId(),"status",status,"code",code,
                    "message","답변 생성이 중단됐어요.","retryable",retryable));
            } catch(IOException disconnected) { }
        }
    }
    private boolean retryable(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof ChatRetrievalException retrieval) return retrieval.retryable();
            if(cause instanceof ChatAnswerException answer) return answer.retryable();
            if(cause instanceof ChatProtocolException || cause instanceof IllegalArgumentException) return false;
            if(cause instanceof OpenAIServiceException http) return AiHttpRetryPolicy.retryable(http.statusCode());
            if(cause instanceof OpenAIIoException || cause instanceof IOException || cause instanceof TimeoutException
                    || cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException) return true;
            if(cause==cause.getCause()) break;
        }
        return false;
    }
    private void event(OutputStream out,String name,Object data) throws IOException {
        out.write(("event: "+name+"\ndata: "+mapper.writeValueAsString(data)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
    }
}
