package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningReplyEvent.ItemResult;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.application.port.out.SignerCredentialResolver.ResolvedSigner;
import com.wpanther.transcript.signing.domain.model.*;
import com.wpanther.transcript.signing.domain.repository.BatchSigningJobRepository;
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
    private final CscAuthorizationPort cscAuthorizationPort;
    private final CscSignaturePort cscSignaturePort;
    private final SignerCredentialResolver credentialResolver;
    private final DocumentStoragePort documentStoragePort;
    private final BatchSagaReplyPort batchSagaReplyPort;
    private final TransactionTemplate transactionTemplate;

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
                            i.getStorageKey()))
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
                byte[] xml = documentStoragePort.downloadByKey(item.getSourceStorageKey());
                String sigId = "Sig-" + UUID.randomUUID();
                // truncate to millis so signingTime round-trips through TIMESTAMPTZ byte-for-byte
                // (1A invariant) — embed on resume must reproduce the exact signed bytes.
                Instant signingTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                XadesPreparation prep = xadesPreparePort.prepare(xml, certificate, signingTime, sigId);
                prepared.add(new Prepared(item, sigId, signingTime, prep.signedInfoDigestBase64()));
            }
            List<String> digests = prepared.stream().map(Prepared::digest).toList();
            String sad = cscAuthorizationPort.authorize(
                    signer.credentialId(), digests, signer.pin());
            List<String> signatures = cscSignaturePort.signHash(
                    digests, sad, signer.credentialId(), signer.hashAlgorithmOid());

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
                byte[] xml = documentStoragePort.downloadByKey(item.getSourceStorageKey());
                byte[] signed = xadesPreparePort.embed(xml, certificate, item.getSigningTime(),
                        item.getSigId(), item.getPendingSignature());
                String key = String.format("%s/%s/%s/signed.xml", command.getFormat().name(),
                        command.getBatchId(), item.getDocumentId());
                var stored = documentStoragePort.upload(signed, key);
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

    private void publishReply(BatchSigningCommand command, BatchSigningJob job) {
        List<ItemResult> results = job.getItems().stream().map(i -> i.isSigned()
                ? ItemResult.signed(i.getDocumentId(), i.getSignedDocUrl(),
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
