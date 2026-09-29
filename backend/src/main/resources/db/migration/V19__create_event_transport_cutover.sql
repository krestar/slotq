-- A quiesced cutover has one durable scan cursor. The metadata fence is committed
-- before the bounded original-event/registration repair scan begins.
CREATE TABLE event_transport_cutover (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    from_transport VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_transport VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authority_epoch BIGINT NOT NULL,
    fence_boundary BIGINT NOT NULL,
    scan_boundary BIGINT NOT NULL DEFAULT 0,
    phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    completed_at DATETIME(6) NULL,
    CONSTRAINT ck_event_transport_cutover_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_event_transport_cutover_transports CHECK (
        from_transport IN ('DB_DIRECT', 'KAFKA') AND
        to_transport IN ('DB_DIRECT', 'KAFKA') AND from_transport <> to_transport
    ),
    CONSTRAINT ck_event_transport_cutover_epoch CHECK (authority_epoch > 1),
    CONSTRAINT ck_event_transport_cutover_scan CHECK (
        scan_boundary >= 0 AND scan_boundary <= fence_boundary
    ),
    CONSTRAINT ck_event_transport_cutover_phase CHECK (
        (phase = 'SCANNING' AND completed_at IS NULL) OR
        (phase = 'READY' AND completed_at IS NOT NULL)
    )
);
