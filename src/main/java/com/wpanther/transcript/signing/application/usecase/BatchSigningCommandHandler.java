package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;
import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningReplyEvent.ItemResult;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.application.port.out.SignerCredentialResolver.ResolvedSigner;
import com.wpanther.transcript.signing.domain.model.*;
import com.wpanther.transcript.signing.domain.repository.BatchSigningJobRepository;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchSigningCommandHandler implements BatchSagaCommandPort {

    private final BatchSigningJobRepository repository;
    private final XadesPreparePort xadesPreparePort;
    private final PadesEmbeddingPort padesEmbeddingPort;
    private final CscAuthorizationPort cscAuthorizationPort;
    private final CscSignaturePort cscSignaturePort;
    private final SignerCredentialResolver credentialResolver;
    private final DocumentStoragePort documentStoragePort;
    private final BatchSagaReplyPort batchSagaReplyPort;
    private final TransactionTemplate transactionTemplate;
    private final StorageProperties properties;

    @Override
    public void handleBatchSigning(BatchSigningCommand command) {
        MDC.put("sagaId", command.getSagaId());
        MDC.put("batchId", command.getBatchId());
        MDC.put("correlationId", command.getCorrelationId());
        try {
            doHandle(command);
        } finally {
            MDC.clear();
        }
    }

    private void doHandle(BatchSigningCommand command) {
        final SigningFormat format = command.getFormat();

        // Phase 1: idempotency by correlationId
        BatchSigningJob job = repository.findByCorrelationId(command.getCorrelationId()).orElse(null);
        if (job != null && job.getStatus() == BatchJobStatus.COMPLETED) {
            log.info("Batch job already COMPLETED — republishing reply");
            publishReply(command, job);
            return;
        }

        // Phase 2/3 (TX1): create the job + items on first delivery
        if (job == null) {
            List<BatchSigningItem> items = command.getItems().stream()
                    .map(i -> BatchSigningItem.create(i.getDocumentId(), i.getDocumentNumber(),
                            i.getStorageKey(), i.getSourceBucket(), i.getTargetStorageKey()))
                    .toList();
            BatchSigningJob newJob = BatchSigningJob.create(command.getCorrelationId(),
                    command.getBatchId(), command.getSagaId(), command.getSignerRole(),
                    command.getFormat(), items);
            newJob.startSigning();
            job = transactionTemplate.execute(s -> repository.save(newJob));
        }

        ResolvedSigner signer = credentialResolver.resolve(command.getSignerRole());
        String certificate = signer.certificatePem();

        // Phase 4: ONE multi-hash CSC call for items that still need a fresh signature.
        // itemsNeedingFreshSignature() excludes SIGNED items AND items already holding a
        // checkpointed signature, so a re-delivery never re-bills the HSM for already-signed work.
        List<BatchSigningItem> needSign = job.itemsNeedingFreshSignature();
        if (!needSign.isEmpty()) {
            // Build (item, sigId, signingTime, digest) tuples WITHOUT mutating the items yet, so
            // there is no persisted "sigId set, signature null" intermediate state — the only
            // persisted states are TX1 (PENDING, no sig) and TX1.5 (signature present).
            record Prepared(BatchSigningItem item, String sigId, Instant signingTime, String digest) {}
            List<Prepared> prepared = new ArrayList<>();
            for (BatchSigningItem item : needSign) {
                byte[] source = downloadSource(item);
                if (format == SigningFormat.PDF) {
                    // PAdES: the signedAttrs digest is deterministic in the PDF bytes
                    // (signingTime is excluded), so the embed pass can recompute it without
                    // a persisted prepare checkpoint — sigId/signingTime are N/A for PDF.
                    String digest = padesEmbeddingPort.computeByteRangeDigest(source)
                            .signedAttrsDigestBase64();
                    prepared.add(new Prepared(item, null, null, digest));
                } else {
                    String sigId = "Sig-" + UUID.randomUUID();
                    // truncate to millis so signingTime round-trips through TIMESTAMPTZ byte-for-byte
                    // (1A invariant) — embed on resume must reproduce the exact signed bytes.
                    Instant signingTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                    XadesPreparation prep = xadesPreparePort.prepare(source, certificate, signingTime, sigId);
                    prepared.add(new Prepared(item, sigId, signingTime, prep.signedInfoDigestBase64()));
                }
            }
            List<String> digests = prepared.stream().map(Prepared::digest).toList();
            String sad = cscAuthorizationPort.authorize(
                    signer.credentialId(), digests, signer.pin());
            var cscResult = cscSignaturePort.signHash(
                    digests, sad, signer.credentialId(), signer.hashAlgorithmOid());
            List<String> signatures = cscResult.signatures();

            // TX1.5: write each item's full checkpoint (sigId + signingTime + signature) at once,
            // then persist. The mapper reads the in-memory domain state (no JPA entity cache), so
            // these in-place mutations + saveAndFlush are what gets written — do not "optimize" by
            // returning a pre-save copy.
            for (int i = 0; i < prepared.size(); i++) {
                Prepared p = prepared.get(i);
                p.item().checkpoint(p.sigId(), p.signingTime(), signatures.get(i));
            }
            final BatchSigningJob checkpointJob = job;
            job = transactionTemplate.execute(s -> repository.save(checkpointJob));
        }

        // Phase 5: embed + upload each item not yet SIGNED (uses its stored signature).
        for (BatchSigningItem item : job.itemsNeedingEmbed()) {
            try {
                byte[] source = downloadSource(item);
                byte[] signed;
                if (format == SigningFormat.PDF) {
                    // Recompute the (deterministic) digest, then embed the CSC signature
                    // into the PDF as a PAdES CMS signature.
                    PadesDigestResult digest = padesEmbeddingPort.computeByteRangeDigest(source);
                    signed = padesEmbeddingPort.embedSignature(digest, item.getPendingSignature(),
                            certificate);
                } else {
                    signed = xadesPreparePort.embed(source, certificate, item.getSigningTime(),
                            item.getSigId(), item.getPendingSignature());
                }
                var stored = documentStoragePort.upload(signed, item.getTargetStorageKey());
                item.markSigned(stored.path(), stored.url(), stored.size());
            } catch (Exception e) {
                log.error("Embed/upload failed for document {}", item.getDocumentId(), e);
                item.markFailed(e.getMessage());
            }
        }

        // Phase 6 (TX2): finalize + publish per-item reply atomically with the outbox write
        final BatchSigningJob finalJob = job;
        final BatchSigningCommand cmd = command;
        transactionTemplate.execute(s -> {
            finalJob.finish();
            repository.save(finalJob);
            publishReply(cmd, finalJob);
            return null;
        });
    }

    /**
     * Fetch the document to be signed, by bucket-qualified reference. The orchestrator now
     * names both the bucket and the key for every phase (XAdES and PAdES alike); a null
     * sourceBucket is the pre-V5 fallback, meaning "signing's own bucket". The allow-list
     * lives in the storage adapter, not here — this method never decides what is readable.
     */
    private byte[] downloadSource(BatchSigningItem item) {
        String bucket = item.getSourceBucket() != null
                ? item.getSourceBucket()
                : properties.getBucketName();
        return documentStoragePort.download(new StorageRef(bucket, item.getSourceStorageKey()));
    }

    private void publishReply(BatchSigningCommand command, BatchSigningJob job) {
        List<ItemResult> results = job.getItems().stream().map(i -> i.isSigned()
                // Return the S3 KEY (not a presigned URL): the orchestrator's
                // TranscriptKeyResolver derives the next phase's (bucket, key) from this
                // value, so it must be a bucket-relative key, never a bearer-bearing URL.
                ? ItemResult.signed(i.getDocumentId(), i.getSignedDocKey(),
                        i.getSignedDocSize() == null ? 0L : i.getSignedDocSize())
                : ItemResult.failed(i.getDocumentId(), i.getErrorMessage())).toList();
        // wrap standalone republish in a tx (outbox adapter is @Transactional(MANDATORY))
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            batchSagaReplyPort.publishBatchReply(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(), command.getBatchId(), job.allItemsSigned(), results);
        } else {
            transactionTemplate.execute(s -> {
                batchSagaReplyPort.publishBatchReply(command.getSagaId(), command.getSagaStep(),
                        command.getCorrelationId(), command.getBatchId(), job.allItemsSigned(), results);
                return null;
            });
        }
    }
}
