CREATE TABLE analysis_results (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    video_id VARCHAR(32) NOT NULL REFERENCES videos (video_id),
    source_job_id UUID NOT NULL UNIQUE REFERENCES analysis_jobs (id),
    result_json TEXT NOT NULL CHECK (length(trim(result_json)) > 0),
    chat_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_result_completion CHECK (
        (chat_ready AND completed_at IS NOT NULL AND completed_at >= created_at)
        OR (NOT chat_ready AND completed_at IS NULL)
    )
);

CREATE TABLE analysis_sections (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES analysis_results (id),
    title VARCHAR(500) NOT NULL CHECK (length(trim(title)) > 0),
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms > start_ms),
    detail_json TEXT NOT NULL CHECK (length(trim(detail_json)) > 0)
);
CREATE INDEX idx_analysis_sections_time ON analysis_sections (analysis_id, start_ms, id);

CREATE TABLE analysis_records (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    video_id VARCHAR(32) NOT NULL REFERENCES videos (video_id),
    analysis_id UUID NOT NULL REFERENCES analysis_results (id),
    analyzed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_analysis_records_owner_result UNIQUE (owner_id, analysis_id)
);
CREATE INDEX idx_analysis_records_owner_recent ON analysis_records (owner_id, analyzed_at DESC, id DESC);
CREATE INDEX idx_analysis_records_owner_video ON analysis_records (owner_id, video_id, analyzed_at DESC, id DESC);

CREATE TABLE analysis_chunks (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    analysis_id UUID NOT NULL REFERENCES analysis_results (id),
    section_ids UUID[] NOT NULL,
    source_segment_ids TEXT[] NOT NULL CHECK (cardinality(source_segment_ids) > 0),
    text TEXT NOT NULL CHECK (length(trim(text)) > 0),
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms > start_ms),
    source_type VARCHAR(32) NOT NULL CHECK (source_type = 'CAPTION'),
    embedding vector NOT NULL,
    embedding_model VARCHAR(255) NOT NULL,
    embedding_dimensions INTEGER NOT NULL CHECK (embedding_dimensions > 0 AND embedding_dimensions = vector_dims(embedding))
);
CREATE INDEX idx_analysis_chunks_owner_analysis ON analysis_chunks (owner_id, analysis_id);
