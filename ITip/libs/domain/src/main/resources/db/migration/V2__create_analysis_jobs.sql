CREATE TABLE videos (
    video_id VARCHAR(32) PRIMARY KEY,
    title VARCHAR(500),
    channel_name VARCHAR(255),
    channel_image_url VARCHAR(2048),
    thumbnail_url VARCHAR(2048),
    duration_ms BIGINT CHECK (duration_ms > 0),
    published_at TIMESTAMPTZ
);

CREATE TABLE analysis_batches (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    playlist_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_analysis_batches_owner_created ON analysis_batches (owner_id, created_at DESC, id DESC);

CREATE TABLE analysis_batch_items (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES analysis_batches (id),
    video_id VARCHAR(32) NOT NULL REFERENCES videos (video_id),
    action VARCHAR(32) NOT NULL CHECK (action IN ('OPEN_RESULT', 'OPEN_JOB', 'COLLECT_CAPTIONS')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('AWAITING_UPLOAD', 'QUEUED', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    job_id UUID,
    analysis_id UUID,
    record_id UUID,
    upload_deadline_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    retryable BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_analysis_batch_video UNIQUE (batch_id, video_id),
    CONSTRAINT ck_item_awaiting_upload CHECK (status <> 'AWAITING_UPLOAD' OR (action = 'COLLECT_CAPTIONS' AND job_id IS NULL AND upload_deadline_at IS NOT NULL)),
    CONSTRAINT ck_item_open_result CHECK (action <> 'OPEN_RESULT' OR (status = 'COMPLETED' AND analysis_id IS NOT NULL AND record_id IS NOT NULL)),
    CONSTRAINT ck_item_open_job CHECK (action <> 'OPEN_JOB' OR job_id IS NOT NULL),
    CONSTRAINT ck_item_failure CHECK (status <> 'FAILED' OR (error_code IS NOT NULL AND error_message IS NOT NULL AND retryable IS NOT NULL))
);
CREATE INDEX idx_batch_items_batch_updated ON analysis_batch_items (batch_id, updated_at, id);
CREATE INDEX idx_batch_items_awaiting_deadline ON analysis_batch_items (upload_deadline_at) WHERE status = 'AWAITING_UPLOAD';

CREATE TABLE caption_uploads (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    batch_item_id UUID NOT NULL REFERENCES analysis_batch_items (id),
    video_id VARCHAR(32) NOT NULL REFERENCES videos (video_id),
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    declared_size_bytes BIGINT NOT NULL CHECK (declared_size_bytes > 0),
    url_expires_at TIMESTAMPTZ NOT NULL,
    generation BIGINT CHECK (generation > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('ISSUED', 'UPLOADED', 'CONSUMED', 'EXPIRED', 'REJECTED')),
    CONSTRAINT uq_caption_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_upload_generation CHECK (status NOT IN ('UPLOADED', 'CONSUMED') OR generation IS NOT NULL)
);
CREATE INDEX idx_caption_uploads_owner_item ON caption_uploads (owner_id, batch_item_id);

CREATE TABLE analysis_jobs (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    batch_id UUID NOT NULL REFERENCES analysis_batches (id),
    batch_item_id UUID NOT NULL REFERENCES analysis_batch_items (id),
    video_id VARCHAR(32) NOT NULL REFERENCES videos (video_id),
    upload_id UUID NOT NULL REFERENCES caption_uploads (id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    stage VARCHAR(32) CHECK (stage IN ('VALIDATING', 'SUMMARIZING', 'INDEXING', 'FINISHING')),
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts = 3),
    next_attempt_at TIMESTAMPTZ,
    run_token UUID,
    lease_until TIMESTAMPTZ,
    previous_job_id UUID REFERENCES analysis_jobs (id),
    analysis_id UUID,
    record_id UUID,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    retryable BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_job_attempts CHECK (attempt <= max_attempts),
    CONSTRAINT ck_job_lease CHECK ((status = 'PROCESSING' AND run_token IS NOT NULL AND lease_until IS NOT NULL AND stage IS NOT NULL) OR (status <> 'PROCESSING' AND run_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_job_next_attempt CHECK ((status IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at IS NOT NULL) OR (status NOT IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at IS NULL)),
    CONSTRAINT ck_job_completed CHECK (status <> 'COMPLETED' OR (stage = 'FINISHING' AND analysis_id IS NOT NULL AND record_id IS NOT NULL)),
    CONSTRAINT ck_job_error CHECK (status NOT IN ('FAILED', 'RETRY_WAIT') OR (error_code IS NOT NULL AND error_message IS NOT NULL AND retryable IS NOT NULL))
);
ALTER TABLE analysis_batch_items ADD CONSTRAINT fk_batch_item_job FOREIGN KEY (job_id) REFERENCES analysis_jobs (id);
CREATE UNIQUE INDEX uq_analysis_jobs_active_owner_video ON analysis_jobs (owner_id, video_id) WHERE status IN ('QUEUED', 'PROCESSING', 'RETRY_WAIT');
CREATE INDEX idx_analysis_jobs_ready ON analysis_jobs (next_attempt_at, created_at, id) WHERE status IN ('QUEUED', 'RETRY_WAIT');
CREATE INDEX idx_analysis_jobs_lease ON analysis_jobs (lease_until) WHERE status = 'PROCESSING';
CREATE INDEX idx_analysis_jobs_owner_video_created ON analysis_jobs (owner_id, video_id, created_at DESC, id DESC);
CREATE INDEX idx_analysis_jobs_batch ON analysis_jobs (batch_id);

CREATE TABLE idempotency_requests (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    operation VARCHAR(100) NOT NULL,
    request_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    target_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_owner_operation_key UNIQUE (owner_id, operation, request_key)
);
