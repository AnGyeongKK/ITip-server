package com.gkk.itip.domain.chat;
public enum MessageStatus {
    QUEUED, GENERATING, COMPLETED, INCOMPLETE, FAILED;
    public static final java.util.Set<MessageStatus> ACTIVE = java.util.Set.of(QUEUED, GENERATING);
    public boolean active() { return ACTIVE.contains(this); }
}
