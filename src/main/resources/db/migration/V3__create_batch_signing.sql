CREATE TABLE batch_signing_jobs (
    id              UUID         PRIMARY KEY,
    correlation_id  VARCHAR(100) NOT NULL,
    batch_id        VARCHAR(100) NOT NULL,
    saga_id         VARCHAR(100) NOT NULL,
    signer_role     VARCHAR(20)  NOT NULL,
    format          VARCHAR(10)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);
-- correlationId is the idempotency key: one job per delivered command.
CREATE UNIQUE INDEX idx_batch_job_correlation ON batch_signing_jobs(correlation_id);
CREATE        INDEX idx_batch_job_batch       ON batch_signing_jobs(batch_id);
CREATE        INDEX idx_batch_job_status      ON batch_signing_jobs(status);

CREATE TABLE batch_signing_items (
    id                 UUID         PRIMARY KEY,
    job_id             UUID         NOT NULL REFERENCES batch_signing_jobs(id) ON DELETE CASCADE,
    document_id        VARCHAR(100) NOT NULL,
    document_number    VARCHAR(50)  NOT NULL,
    source_storage_key VARCHAR(500) NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    sig_id             VARCHAR(100),
    signing_time       TIMESTAMPTZ,
    pending_signature  TEXT,
    signed_doc_key     VARCHAR(500),
    signed_doc_url     VARCHAR(1000),
    signed_doc_size    BIGINT,
    error_message      TEXT
);
CREATE UNIQUE INDEX idx_batch_item_job_doc ON batch_signing_items(job_id, document_id);
-- (no standalone status index: items are always loaded via the job FK / unique (job_id, document_id))

COMMENT ON COLUMN batch_signing_items.pending_signature IS 'CSC signature persisted at the per-item TX1.5 checkpoint; embed re-runs prepare(sig_id, signing_time) and injects this';
