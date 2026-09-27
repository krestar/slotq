-- Noncanonical, optional observation metadata. No business/event/dedupe identity or version change.
-- Existing immutable records remain valid and may have no observation origin.
ALTER TABLE event_records
    ADD COLUMN origin_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN origin_trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN origin_span_id CHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL;
