package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.conversation.service.ChatScopeQuery;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.request.RequestFingerprint;
import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.chat.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import static com.gkk.itip.common.exception.ErrorCode.*;

@Service
public class ChatGenerationStore {
    private static final String SEND = "SEND_CHAT_MESSAGE";
    private final ChatConversationRepository rooms;
    private final ChatMessageRepository messages;
    private final IdempotencyRequestRepository requests;
    private final ChatScopeQuery scopes;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ChatCitationQuery citations;
    private final ChatMessageSourceRepository sources;
    private final ChatHistoryQuery history;
    public ChatGenerationStore(ChatConversationRepository rooms, ChatMessageRepository messages,
            IdempotencyRequestRepository requests, ChatScopeQuery scopes, JdbcTemplate jdbc, JsonMapper mapper, Clock clock,
            ChatCitationQuery citations, ChatMessageSourceRepository sources,ChatHistoryQuery history) {
        this.rooms=rooms; this.messages=messages; this.requests=requests; this.scopes=scopes; this.jdbc=jdbc; this.mapper=mapper; this.clock=clock;
        this.citations=citations; this.sources=sources;
        this.history=history;
    }
    private record Request(UUID room, SendMessageRequest body) {}

    @Transactional
    public GenerationTicket begin(String owner, UUID roomId, UUID key, SendMessageRequest request) {
        if (!key.equals(request.clientRequestId())) throw new BusinessException(INVALID_REQUEST);
        if (request.content() == null || request.content().isBlank() || request.content().length() > ChatLimits.MAX_QUESTION_CHARS)
            throw new BusinessException(INVALID_CHAT_MESSAGE);
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", "itip-chat-send:"+owner+":"+key);
        var room = rooms.lockOwned(roomId,owner).orElseThrow(()->new BusinessException(RESOURCE_NOT_FOUND));
        String hash = RequestFingerprint.of(mapper,new Request(roomId,request));
        var previous = requests.findByOwnerIdAndOperationAndKey(owner,SEND,key).orElse(null);
        if (previous != null) throw new BusinessException(previous.matches(hash) ? DUPLICATE_CHAT_REQUEST : IDEMPOTENCY_CONFLICT);
        expire(roomId);
        if (messages.existsByConversationIdAndStatusIn(roomId,MessageStatus.ACTIVE)) throw new BusinessException(CHAT_GENERATION_IN_PROGRESS);
        validateRetry(roomId,request);
        var scope = scopes.readScope(room.getScopeJson());
        var selection = scopes.resolve(owner,scope);
        if (selection.status().equals("UNAVAILABLE")) throw new BusinessException(SCOPE_UNAVAILABLE);
        if (selection.records().isEmpty()) throw new BusinessException(EMPTY_CHAT_SCOPE);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long position = room.acceptQuestion(request.content(),now);
        var question = messages.saveAndFlush(ChatMessage.question(roomId,position,key,hash,request.content(),now));
        UUID token = UUID.randomUUID();
        var answer = messages.saveAndFlush(ChatMessage.answer(question,selection.records().size(),request.retryOfMessageId(),token,now.plus(ChatLimits.GENERATION_LEASE)));
        requests.save(new IdempotencyRequest(owner,SEND,key,hash,answer.getId(),now));
        return new GenerationTicket(owner,roomId,question.getId(),answer.getId(),key,token,selection.records(),
            history.select(owner,roomId,selection.records()),scope.focusSectionId());
    }
    private void validateRetry(UUID room, SendMessageRequest request) {
        if (request.retryOfMessageId() == null) return;
        var old = messages.findByIdAndConversationId(request.retryOfMessageId(),room).orElseThrow(()->new BusinessException(CHAT_MESSAGE_NOT_RETRYABLE));
        if (old.getRole()!=MessageRole.ASSISTANT || (old.getStatus()!=MessageStatus.FAILED && old.getStatus()!=MessageStatus.INCOMPLETE)
                || !Boolean.TRUE.equals(old.getRetryable())) throw new BusinessException(CHAT_MESSAGE_NOT_RETRYABLE);
        var question = messages.findByIdAndConversationId(old.getReplyToMessageId(),room).orElseThrow(()->new BusinessException(CHAT_MESSAGE_NOT_RETRYABLE));
        if (!question.getContent().equals(request.content())) throw new BusinessException(CHAT_MESSAGE_NOT_RETRYABLE);
    }
    @Transactional
    public boolean checkpoint(GenerationTicket ticket, String text) {
        var message = current(ticket);
        if (message == null) return false;
        message.checkpoint(text); return true;
    }
    @Transactional
    public MessageStatus fail(GenerationTicket ticket, String text, String code, boolean retryable) {
        var message = current(ticket);
        if (message == null) return null;
        message.fail(text,code,retryable); return message.getStatus();
    }
    @Transactional
    public boolean complete(GenerationTicket ticket, String text, FinishReason reason, List<ChatCitation> input, List<ChatCitation> candidates) {
        var room=rooms.lockOwned(ticket.conversationId(),ticket.ownerId()).orElseThrow(()->new ChatProtocolException());
        var message=current(ticket);
        if(message==null) return false;
        if(text.isBlank() || reason==null || (reason==FinishReason.INSUFFICIENT_EVIDENCE && !input.isEmpty())
                || (reason!=FinishReason.INSUFFICIENT_EVIDENCE && input.isEmpty())) throw new ChatProtocolException();
        var current=scopes.resolve(ticket.ownerId(),scopes.readScope(room.getScopeJson()));
        if(current.status().equals("UNAVAILABLE")) throw new ChatProtocolException();
        var verified=citations.validate(ticket,input,current.records(),candidates);
        int used=(int)verified.stream().map(ChatMessageSource::getRecordId).distinct().count();
        sources.saveAll(verified);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        message.complete(text,reason,used,now); room.touch(now);
        return true;
    }
    @Transactional(readOnly=true)
    public GenerationTicket refresh(GenerationTicket ticket) {
        var room=rooms.findByIdAndOwnerId(ticket.conversationId(),ticket.ownerId()).orElseThrow(ChatProtocolException::new);
        var current=scopes.resolve(ticket.ownerId(),scopes.readScope(room.getScopeJson()));
        if(current.status().equals("UNAVAILABLE")) throw new ChatProtocolException();
        var records=current.records().stream().filter(ticket.records()::contains).toList();
        return new GenerationTicket(ticket.ownerId(),ticket.conversationId(),ticket.userMessageId(),ticket.assistantMessageId(),
            ticket.clientRequestId(),ticket.token(),records,history.select(ticket.ownerId(),ticket.conversationId(),records),ticket.focusSectionId());
    }
    private ChatMessage current(GenerationTicket ticket) {
        var message = messages.lock(ticket.assistantMessageId()).orElse(null);
        return message != null && message.ownedGeneration(ticket.token(),clock.instant()) ? message : null;
    }
    @Transactional
    public int recoverExpired() { return expire(null); }
    private int expire(UUID room) {
        return jdbc.update("""
            update chat_messages set status=case when content='' then 'FAILED' else 'INCOMPLETE' end,
              error_code='CHAT_GENERATION_EXPIRED',error_message='답변 생성이 중단됐어요.',retryable=true,
              generation_token=null,lease_until=null
            where status in ('QUEUED','GENERATING') and lease_until<=?
            """+(room==null?"":" and conversation_id=?"),room==null?
                new Object[]{Timestamp.from(clock.instant())}:new Object[]{Timestamp.from(clock.instant()),room});
    }
}
