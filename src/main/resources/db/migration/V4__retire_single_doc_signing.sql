-- Retire the single-document signing path (see
-- docs/superpowers/specs/2026-07-10-retire-single-document-signing-path-design.md).
--
-- RENAME rather than DROP. Renaming satisfies ddl-auto=validate (no entity maps this
-- table any more) while keeping any rows recoverable. The irreversible DROP is deferred
-- to a later migration, once the table is confirmed empty in every environment.
--
-- Postgres carries indexes, constraints and comments across a table rename, so nothing
-- else needs restating here.
ALTER TABLE signed_transcript_documents RENAME TO signed_transcript_documents_retired;

COMMENT ON TABLE signed_transcript_documents_retired IS
    'RETIRED 2026-07-10 — was signed_transcript_documents, owned by the deleted '
    'single-document signing path. Safe to DROP once confirmed empty in all environments.';
