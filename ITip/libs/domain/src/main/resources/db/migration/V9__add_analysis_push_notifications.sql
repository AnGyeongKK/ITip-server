CREATE TABLE notification_devices (
    id UUID PRIMARY KEY,
    installation_id UUID NOT NULL UNIQUE,
    owner_id VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL CHECK (platform = 'ANDROID'),
    fcm_token VARCHAR(2048) UNIQUE,
    permission VARCHAR(16) NOT NULL CHECK (permission IN ('GRANTED', 'DENIED', 'NOT_DETERMINED')),
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_notification_devices_owner_active ON notification_devices (owner_id)
    WHERE permission = 'GRANTED' AND fcm_token IS NOT NULL;

ALTER TABLE analysis_batches ADD COLUMN push_pending BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_analysis_batches_push_pending ON analysis_batches (owner_id) WHERE push_pending;

CREATE TABLE analysis_push_deliveries (
    batch_id UUID NOT NULL REFERENCES analysis_batches (id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES notification_devices (id) ON DELETE CASCADE,
    installation_id UUID NOT NULL,
    analysis_id UUID,
    completed_count INTEGER NOT NULL CHECK (completed_count >= 0),
    failed_count INTEGER NOT NULL CHECK (failed_count >= 0),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENT', 'DISCARDED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    lease_id UUID,
    PRIMARY KEY (batch_id, installation_id)
);
CREATE INDEX idx_analysis_push_due ON analysis_push_deliveries (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX idx_analysis_push_device ON analysis_push_deliveries (device_id);
