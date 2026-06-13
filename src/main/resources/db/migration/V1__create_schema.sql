-- Transcript Signing Service schema

-- ============================================================
-- signed_transcript_documents
-- ============================================================
CREATE TABLE signed_transcript_documents (
    id                    UUID            PRIMARY KEY,
    document_id           VARCHAR(100)    NOT NULL,
    document_number       VARCHAR(50)     NOT NULL,
    format                VARCHAR(10)     NOT NULL,
    original_doc_path     VARCHAR(500)    NOT NULL,
    original_doc_url      VARCHAR(1000)   NOT NULL,
    original_doc_size     BIGINT          NOT NULL,
    signed_doc_path       VARCHAR(500),
    signed_doc_url        VARCHAR(1000),
    signed_doc_size       BIGINT,
    transaction_id        VARCHAR(200),
    pending_signature     TEXT,
    certificate           TEXT,
    signature_level       VARCHAR(50),
    signature_timestamp   TIMESTAMPTZ,
    status                VARCHAR(20)     NOT NULL,
    error_message         TEXT,
    retry_count           INTEGER         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               BIGINT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_signed_transcript_document_id     ON signed_transcript_documents(document_id);
CREATE        INDEX idx_signed_transcript_document_number ON signed_transcript_documents(document_number);
CREATE        INDEX idx_signed_transcript_status          ON signed_transcript_documents(status);
CREATE        INDEX idx_signed_transcript_format          ON signed_transcript_documents(format);
CREATE        INDEX idx_signed_transcript_created_at      ON signed_transcript_documents(created_at);

COMMENT ON TABLE  signed_transcript_documents IS 'Signed transcript document metadata and signing lifecycle';
COMMENT ON COLUMN signed_transcript_documents.document_id      IS 'Business key — unique per document, used for idempotency';
COMMENT ON COLUMN signed_transcript_documents.format           IS 'XML or PDF';
COMMENT ON COLUMN signed_transcript_documents.transaction_id   IS 'CSC responseId; persisted in TX1.5 after signHash — enables retry to skip CSC';
COMMENT ON COLUMN signed_transcript_documents.pending_signature IS 'Raw base64 signature from CSC signHash; persisted in TX1.5; cleared to null in TX2';
COMMENT ON COLUMN signed_transcript_documents.retry_count      IS 'Incremented only for post-TX1 failures';
COMMENT ON COLUMN signed_transcript_documents.version          IS 'Optimistic lock version';

-- ============================================================
-- outbox_events (transactional outbox / Camel relay)
-- ============================================================
CREATE TABLE outbox_events (
    id              UUID            PRIMARY KEY,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    retry_count     INTEGER         NOT NULL DEFAULT 0,
    error_message   TEXT,
    topic           VARCHAR(255),
    partition_key   VARCHAR(255),
    headers         TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status     ON outbox_events(status);
CREATE INDEX idx_outbox_created_at ON outbox_events(created_at);
CREATE INDEX idx_outbox_aggregate  ON outbox_events(aggregate_id, aggregate_type);
