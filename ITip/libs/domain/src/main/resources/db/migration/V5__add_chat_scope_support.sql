ALTER TABLE chat_conversations ADD COLUMN initial_record_count INTEGER NOT NULL DEFAULT 0 CHECK (initial_record_count >= 0);

CREATE TABLE folders (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_folders_owner_ref UNIQUE (id,owner_id)
);
CREATE INDEX idx_folders_owner_created ON folders(owner_id,created_at DESC,id DESC);
ALTER TABLE analysis_records ADD CONSTRAINT uq_analysis_records_owner_ref UNIQUE (id,owner_id);
CREATE TABLE folder_records (
    folder_id UUID NOT NULL,
    record_id UUID NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (folder_id,record_id),
    FOREIGN KEY (folder_id,owner_id) REFERENCES folders(id,owner_id) ON DELETE CASCADE,
    FOREIGN KEY (record_id,owner_id) REFERENCES analysis_records(id,owner_id) ON DELETE CASCADE
);
CREATE INDEX idx_folder_records_record ON folder_records(record_id);
