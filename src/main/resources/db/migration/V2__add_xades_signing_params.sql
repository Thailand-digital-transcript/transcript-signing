-- XAdES two-pass remote signing needs the signing time + signature element id persisted at the
-- TX1.5 checkpoint, so embed (pass 2) reproduces the exact bytes that CSC signed in pass 1.
ALTER TABLE signed_transcript_documents ADD COLUMN sig_id        VARCHAR(100);
ALTER TABLE signed_transcript_documents ADD COLUMN signing_time  TIMESTAMPTZ;

COMMENT ON COLUMN signed_transcript_documents.sig_id       IS 'ds:Signature Id chosen at prepare; reused at embed for deterministic signed bytes';
COMMENT ON COLUMN signed_transcript_documents.signing_time IS 'xades:SigningTime chosen at prepare; reused at embed';
