ALTER TABLE analysis_records
    ADD COLUMN reading_tab VARCHAR(16),
    ADD COLUMN reading_section_id UUID,
    ADD COLUMN last_viewed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_analysis_records_reading_position CHECK (
        (reading_tab IS NULL AND reading_section_id IS NULL AND last_viewed_at IS NULL)
        OR (reading_tab IS NOT NULL AND reading_tab IN ('SUMMARY', 'MINDMAP', 'DETAIL') AND last_viewed_at IS NOT NULL)
    );
