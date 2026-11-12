package com.gkk.itip.chat.stream;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component
@ConditionalOnProperty(name="itip.chat.lease-recovery-enabled",havingValue="true",matchIfMissing=true)
public class ChatLeaseRecovery {
    private final ChatGenerationStore store;
    public ChatLeaseRecovery(ChatGenerationStore store) { this.store=store; }
    @Scheduled(fixedDelay=10000) public void recover() { store.recoverExpired(); }
}
