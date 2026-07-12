-- A resumed batch (BatchSigningResumeIT) re-reads its source from this row, not from the
-- command. Once the source can live in a DIFFERENT bucket, the bucket must be persisted
-- alongside the key or a resumed registrar round cannot find its input.
--
-- Nullable: rows written before this change have no bucket, and the handler reads NULL as
-- "signing's own bucket", preserving the old behaviour for in-flight batches.
ALTER TABLE batch_signing_items ADD COLUMN source_bucket VARCHAR(63);

-- target_storage_key must be persisted too, not just carried on the in-memory item: every
-- BatchSigningCommandHandler.doHandle() call re-reads the job through
-- BatchSigningJobRepositoryAdapter.save()/findByCorrelationId() (mapper.toDomain from the
-- JPA entity) BEFORE Phase 5's embed/upload loop runs -- even on the very first delivery,
-- because Phase 2/3 immediately saveAndFlush-then-reloads the freshly created job. Without
-- this column, the target key set on the in-memory BatchSigningItem.create() would be lost
-- the moment the job round-trips through the database, and Phase 5's
-- documentStoragePort.upload(signed, item.getTargetStorageKey()) would upload to null.
ALTER TABLE batch_signing_items ADD COLUMN target_storage_key VARCHAR(500);
