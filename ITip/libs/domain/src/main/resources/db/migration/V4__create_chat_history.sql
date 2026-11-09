CREATE TABLE chat_conversations (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    scope_json TEXT NOT NULL CHECK (length(trim(scope_json)) > 0),
    scope_label VARCHAR(500) NOT NULL,
    title VARCHAR(160) NOT NULL,
    last_question TEXT,
    next_position BIGINT NOT NULL DEFAULT 0 CHECK (next_position >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at)
);
CREATE INDEX idx_chat_conversations_recent ON chat_conversations(owner_id, updated_at DESC, id DESC);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    position BIGINT NOT NULL CHECK (position > 0),
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    client_request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    reply_to_message_id UUID,
    retry_of_message_id UUID,
    status VARCHAR(32),
    finish_reason VARCHAR(32) CHECK (finish_reason IN ('STOP','LENGTH','INSUFFICIENT_EVIDENCE')),
    scope_record_count INTEGER,
    used_record_count INTEGER,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    retryable BOOLEAN,
    generation_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_chat_message_room_id UNIQUE (conversation_id,id),
    CONSTRAINT uq_chat_message_position UNIQUE (conversation_id,position),
    CONSTRAINT uq_chat_message_request_role UNIQUE (conversation_id,client_request_id,role),
    CONSTRAINT fk_chat_reply_same_room FOREIGN KEY (conversation_id,reply_to_message_id)
        REFERENCES chat_messages(conversation_id,id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_retry_same_room FOREIGN KEY (conversation_id,retry_of_message_id)
        REFERENCES chat_messages(conversation_id,id),
    CONSTRAINT ck_chat_message_role CHECK (
        (role='USER' AND status IS NULL AND reply_to_message_id IS NULL AND retry_of_message_id IS NULL
            AND scope_record_count IS NULL AND used_record_count IS NULL AND retryable IS NULL)
        OR (role='ASSISTANT' AND status IS NOT NULL AND status IN ('QUEUED','GENERATING','COMPLETED','INCOMPLETE','FAILED')
            AND reply_to_message_id IS NOT NULL AND scope_record_count IS NOT NULL AND scope_record_count >= 0
            AND used_record_count IS NOT NULL AND used_record_count BETWEEN 0 AND scope_record_count AND retryable IS NOT NULL)
    ),
    CONSTRAINT ck_chat_message_completion CHECK (CASE WHEN status='COMPLETED'
        THEN finish_reason IS NOT NULL AND completed_at IS NOT NULL AND completed_at >= created_at AND NOT retryable
        ELSE finish_reason IS NULL AND completed_at IS NULL END),
    CONSTRAINT ck_chat_message_error CHECK (CASE WHEN status IN ('INCOMPLETE','FAILED')
        THEN error_code IS NOT NULL AND error_message IS NOT NULL
        ELSE error_code IS NULL AND error_message IS NULL END),
    CONSTRAINT ck_chat_message_lease CHECK (CASE WHEN status IN ('QUEUED','GENERATING')
        THEN generation_token IS NOT NULL AND lease_until IS NOT NULL
        ELSE generation_token IS NULL AND lease_until IS NULL END)
);
CREATE UNIQUE INDEX uq_chat_active_answer ON chat_messages(conversation_id)
    WHERE role='ASSISTANT' AND status IN ('QUEUED','GENERATING');
CREATE INDEX idx_chat_messages_lease ON chat_messages(lease_until) WHERE status IN ('QUEUED','GENERATING');

CREATE TABLE chat_message_sources (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    source_id VARCHAR(64) NOT NULL,
    record_id UUID NOT NULL,
    analysis_id UUID NOT NULL,
    section_id UUID NOT NULL,
    video_id VARCHAR(32) NOT NULL,
    title VARCHAR(500),
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms > start_ms),
    analyzed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_chat_source_message_ref UNIQUE (message_id,source_id)
);
