package com.gkk.itip.chat.generation;

import com.gkk.itip.chat.stream.ChatCitation;
import com.gkk.itip.chat.stream.ChatLimits;
import com.gkk.itip.chat.stream.ChatProtocolException;
import com.gkk.itip.domain.chat.FinishReason;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.EOFException;
import java.util.*;

/** 요청별 상태. answer 문자열만 점진 해제하고, 출처/JSON 전체는 공급자 정상 종료 뒤 검증한다. */
public final class AnswerJsonStream {
    private static final String PREFIX="{\"answer\":";
    private final StringBuilder raw=new StringBuilder(),answer=new StringBuilder();
    private int prefix,unicodeDigits,unicodeValue;
    private boolean inAnswer,closed,escape;
    private char pendingHigh;
    private String finish;
    private record Citation(String contextId,UUID sectionId) {}
    private record Output(String answer,List<Citation> citations,String evidenceStatus) {}
    public record Result(String answer,FinishReason reason,List<ChatCitation> citations) {}

    public String accept(ChatResponse response) {
        if(response==null || response.getResults().size()>1) throw new ChatProtocolException();
        if(response.getResults().isEmpty()) return "";
        var generation=response.getResult();
        if(generation.getOutput().getMetadata().get("refusal") instanceof String refusal && !refusal.isBlank())
            throw new ChatAnswerException("CHAT_AI_REFUSED",false);
        if(!generation.getOutput().getToolCalls().isEmpty()) throw new ChatProtocolException();
        String text=generation.getOutput().getText();
        var delta=new StringBuilder();
        if(text!=null) {
            if(raw.length()+text.length()>ChatLimits.MAX_ANSWER_CHARS*6+16384) throw new ChatProtocolException();
            raw.append(text);
            for(int n=0;n<text.length();n++) consume(text.charAt(n),delta);
        }
        String terminal=generation.getMetadata().getFinishReason();
        if(terminal!=null && !terminal.isBlank()) finish=terminal;
        return delta.toString();
    }
    private void consume(char c,StringBuilder delta) {
        if(closed) return;
        if(!inAnswer) {
            if(prefix<PREFIX.length()) {
                if(Character.isWhitespace(c) && (prefix==0 || prefix==1 || prefix==9)) return;
                if(c!=PREFIX.charAt(prefix++)) throw new ChatProtocolException();
                return;
            }
            if(Character.isWhitespace(c)) return;
            if(c!='"') throw new ChatProtocolException();
            inAnswer=true;return;
        }
        if(unicodeDigits>0) {
            int hex=Character.digit(c,16);if(hex<0) throw new ChatProtocolException();
            unicodeValue=unicodeValue*16+hex;
            if(--unicodeDigits==0) emit((char)unicodeValue,delta);
        } else if(escape) {
            escape=false;
            if(c=='u') {unicodeDigits=4;unicodeValue=0;return;}
            emit(switch(c) {case '"','\\','/'->c;case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';default->throw new ChatProtocolException();},delta);
        } else if(c=='\\') escape=true;
        else if(c=='"') {if(pendingHigh!=0) throw new ChatProtocolException();closed=true;}
        else {if(c<0x20) throw new ChatProtocolException();emit(c,delta);}
    }
    private void emit(char c,StringBuilder delta) {
        if(pendingHigh!=0) {
            if(!Character.isLowSurrogate(c)) throw new ChatProtocolException();
            answer.append(pendingHigh).append(c);delta.append(pendingHigh).append(c);pendingHigh=0;
        } else if(Character.isHighSurrogate(c)) pendingHigh=c;
        else {if(Character.isLowSurrogate(c)) throw new ChatProtocolException();answer.append(c);delta.append(c);}
        if(answer.length()>ChatLimits.MAX_ANSWER_CHARS) throw new ChatProtocolException();
    }
    public Result finish(ChatAnswerGenerator.Prepared prepared) throws EOFException {
        if(finish==null) throw new EOFException("model stream ended without provider finish metadata");
        if(finish.equalsIgnoreCase("LENGTH")) throw new ChatAnswerException("CHAT_ANSWER_TOO_LONG",false);
        if(!finish.equalsIgnoreCase("STOP")) throw new ChatAnswerException("CHAT_AI_REFUSED",false);
        if(!closed || escape || unicodeDigits!=0 || pendingHigh!=0) throw new ChatProtocolException();
        Output output;
        try {
            output=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build().readValue(raw.toString(),Output.class);
        } catch(RuntimeException invalid) { throw new ChatProtocolException(); }
        if(output==null || output.answer()==null || output.answer().isBlank() || !output.answer().contentEquals(answer)
                || output.citations()==null || output.citations().size()>ChatLimits.MAX_SOURCES) throw new ChatProtocolException();
        boolean insufficient="INSUFFICIENT_EVIDENCE".equals(output.evidenceStatus());
        if(!insufficient && !"SUPPORTED".equals(output.evidenceStatus()) || insufficient!=output.citations().isEmpty())
            throw new ChatProtocolException();
        var citations=new LinkedHashSet<ChatCitation>();
        for(var citation:output.citations()) {
            if(citation==null || citation.contextId()==null || !citation.contextId().matches("C[1-9][0-9]*")) throw new ChatProtocolException();
            int index;
            try {index=Integer.parseInt(citation.contextId().substring(1))-1;}catch(NumberFormatException invalid){throw new ChatProtocolException();}
            if(index<0 || index>=prepared.contexts().size()) throw new ChatProtocolException();
            var context=prepared.contexts().get(index);
            if(!context.sectionIds().contains(citation.sectionId())) throw new ChatProtocolException();
            citations.add(new ChatCitation(context.recordId(),context.chunkId(),citation.sectionId()));
        }
        return new Result(output.answer(),insufficient?FinishReason.INSUFFICIENT_EVIDENCE:FinishReason.STOP,List.copyOf(citations));
    }
}
