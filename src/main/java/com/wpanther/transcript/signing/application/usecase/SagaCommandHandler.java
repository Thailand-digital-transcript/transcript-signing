package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.SignHashResult;
import com.wpanther.transcript.signing.application.dto.SigningResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.dto.event.CompensateTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.domain.model.*;
import com.wpanther.transcript.signing.domain.repository.SignedTranscriptDocumentRepository;
import com.wpanther.transcript.signing.infrastructure.config.properties.SigningProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaCommandHandler implements SagaCommandPort {

    private final SignedTranscriptDocumentRepository repository;
    private final XadesTranscriptSigningService xadesSigningService;
    private final PadesTranscriptSigningService padesSigningService;
    private final DocumentStoragePort documentStoragePort;
    private final DocumentDownloadPort documentDownloadPort;
    private final SagaReplyPort sagaReplyPort;
    private final TranscriptSignedEventPort transcriptSignedEventPort;
    private final DocumentArchivePort documentArchivePort;
    private final TransactionTemplate transactionTemplate;
    private final SigningProperties signingProperties;

    @Override
    public void handleSigningCommand(ProcessTranscriptSigningCommand command) {
        setupMdc(command.getSagaId(), command.getCorrelationId(), command.getDocumentId(),
                command.getDocumentNumber());
        try {
            doHandleSigning(command);
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handleCompensationCommand(CompensateTranscriptSigningCommand command) {
        setupMdc(command.getSagaId(), command.getCorrelationId(), command.getDocumentId(), null);
        try {
            doHandleCompensation(command);
        } finally {
            MDC.clear();
        }
    }

    private void doHandleSigning(ProcessTranscriptSigningCommand command) {
        // Phase 0: format validation
        if (command.getFormat() == null) {
            log.warn("Signing command missing format field — publishing FAILURE");
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(), "Missing or unrecognised format field");
            return;
        }
        SigningFormat format = command.getFormat();
        String documentId = command.getDocumentId();

        // Phase 1: idempotency check
        SignedTranscriptDocument document = repository.findByDocumentId(documentId).orElse(null);
        if (document != null && document.getStatus() == SigningStatus.COMPLETED) {
            log.info("Document already COMPLETED — republishing success reply");
            sagaReplyPort.publishSuccess(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(), document.getSignedDocUrl(),
                    document.getSignedDocSize(), format);
            return;
        }

        // Get original bytes (needed for Phase 4b embedding)
        byte[] originalBytes = resolveOriginalBytes(command, format, document);
        if (originalBytes == null) {
            // resolveOriginalBytes already logged the error; surface it to the saga orchestrator
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(), "Failed to download PDF from source URL");
            return;
        }

        // Phase 2 + Phase 3: upload original and persist SIGNING state (first attempt only)
        if (document == null) {
            String originalKey = buildOriginalKey(format, documentId, 0);
            StorageResult orig;
            try {
                orig = documentStoragePort.upload(originalBytes, originalKey);
            } catch (Exception e) {
                log.error("Phase 2: Failed to upload original document to S3", e);
                sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                        command.getCorrelationId(), "Pre-signing S3 upload failed: " + e.getMessage());
                return;
            }

            final StorageResult origFinal = orig;
            final boolean[] maxRetriesHit = {false};
            document = transactionTemplate.execute(status -> {
                var newDoc = SignedTranscriptDocument.create(documentId, command.getDocumentNumber(),
                        format, origFinal.path(), origFinal.url(), origFinal.size());
                newDoc.startSigning();
                if (newDoc.isMaxRetriesExceeded(signingProperties.getMaxRetries())) {
                    newDoc.markFailed("Max retries exceeded");
                    repository.save(newDoc);
                    maxRetriesHit[0] = true;
                    return null;
                }
                repository.save(newDoc);
                return newDoc;
            });

            if (document == null || maxRetriesHit[0]) {
                sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                        command.getCorrelationId(), "Max retry attempts exceeded");
                return;
            }
        } else {
            // Retry with existing document: check max retries in TX
            final SignedTranscriptDocument existingDoc = document;
            final boolean[] maxRetriesHit = {false};
            document = transactionTemplate.execute(status -> {
                if (existingDoc.isMaxRetriesExceeded(signingProperties.getMaxRetries())) {
                    existingDoc.markFailed("Max retries exceeded");
                    repository.save(existingDoc);
                    maxRetriesHit[0] = true;
                    return null;
                }
                if (existingDoc.getStatus() != SigningStatus.SIGNING) {
                    existingDoc.startSigning();
                }
                repository.save(existingDoc);
                return existingDoc;
            });

            if (document == null || maxRetriesHit[0]) {
                sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                        command.getCorrelationId(), "Max retry attempts exceeded");
                return;
            }
        }

        // Phase 4a: CSC signing or short-circuit via TX1.5 checkpoint
        SignHashResult signHashResult;
        if (document.getTransactionId() != null) {
            log.info("Phase 4a: TX1.5 short-circuit — reusing stored pendingSignature");
            signHashResult = new SignHashResult(
                    document.getTransactionId(),
                    document.getPendingSignature(),
                    document.getCertificate());
        } else {
            try {
                signHashResult = format == SigningFormat.XML
                        ? xadesSigningService.computeAndSign(originalBytes)
                        : padesSigningService.computeAndSign(originalBytes);
            } catch (Exception e) {
                log.error("Phase 4a: CSC signing failed", e);
                final SignedTranscriptDocument failDoc = document;
                transactionTemplate.execute(status -> {
                    failDoc.markFailed(e.getMessage());
                    return repository.save(failDoc);
                });
                sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                        command.getCorrelationId(), "CSC signing failed: " + e.getMessage());
                return;
            }

            // TX1.5: persist transactionId and pendingSignature
            final SignHashResult signHashFinal = signHashResult;
            final SignedTranscriptDocument checkpointDoc = document;
            transactionTemplate.execute(status -> {
                checkpointDoc.saveTransactionCheckpoint(
                        signHashFinal.transactionId(),
                        signHashFinal.pendingSignature(),
                        signHashFinal.certificate());
                return repository.save(checkpointDoc);
            });
        }

        // Phase 4b: embed signature + upload signed document
        SigningResult signingResult;
        try {
            signingResult = format == SigningFormat.XML
                    ? xadesSigningService.embedAndUpload(originalBytes, signHashResult.pendingSignature(),
                            signHashResult.certificate(), documentId, document.getRetryCount())
                    : padesSigningService.embedAndUpload(originalBytes, signHashResult.pendingSignature(),
                            signHashResult.certificate(), documentId, document.getRetryCount());
        } catch (Exception e) {
            log.error("Phase 4b: Embed/upload failed", e);
            final SignedTranscriptDocument failDoc = document;
            transactionTemplate.execute(status -> {
                failDoc.markFailed(e.getMessage());
                return repository.save(failDoc);
            });
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(), "Signature embed/upload failed: " + e.getMessage());
            return;
        }

        // Phase 5 (TX2): mark COMPLETED + publish 3 outbox events atomically
        final SignedTranscriptDocument completeDoc = document;
        final SigningResult signingResultFinal = signingResult;
        final ProcessTranscriptSigningCommand cmd = command;
        transactionTemplate.execute(status -> {
            completeDoc.markCompleted(
                    signingResultFinal.signedDocPath(),
                    signingResultFinal.signedDocUrl(),
                    signingResultFinal.signedDocSize(),
                    signingResultFinal.signatureLevel(),
                    signingResultFinal.signatureTimestamp());
            repository.save(completeDoc);

            sagaReplyPort.publishSuccess(cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId(),
                    signingResultFinal.signedDocUrl(), signingResultFinal.signedDocSize(), format);
            transcriptSignedEventPort.publish(documentId, completeDoc.getDocumentNumber(), format,
                    signingResultFinal.signedDocUrl(), signingResultFinal.signatureLevel(),
                    signingResultFinal.signatureTimestamp());
            documentArchivePort.publish(documentId, completeDoc.getDocumentNumber(), format,
                    signingResultFinal.signedDocPath(), signingResultFinal.signatureTimestamp());
            return null;
        });
    }

    private void doHandleCompensation(CompensateTranscriptSigningCommand command) {
        var document = repository.findByDocumentId(command.getDocumentId()).orElse(null);
        if (document == null) {
            log.info("Compensation: no document found for {} — publishing COMPENSATED", command.getDocumentId());
            sagaReplyPort.publishCompensated(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId());
            return;
        }

        // Delete S3 artifacts (best-effort)
        tryDelete(document.getOriginalDocPath());
        if (document.getSignedDocPath() != null) {
            tryDelete(document.getSignedDocPath());
        }

        // TX: delete DB record + publish COMPENSATED
        final SignedTranscriptDocument doc = document;
        final CompensateTranscriptSigningCommand cmd = command;
        transactionTemplate.execute(status -> {
            repository.deleteById(doc.getId());
            sagaReplyPort.publishCompensated(cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId());
            return null;
        });
    }

    private byte[] resolveOriginalBytes(ProcessTranscriptSigningCommand command,
                                         SigningFormat format,
                                         SignedTranscriptDocument existingDoc) {
        if (format == SigningFormat.XML) {
            return command.getXmlContent().getBytes(StandardCharsets.UTF_8);
        }
        if (existingDoc != null && existingDoc.getOriginalDocPath() != null) {
            return documentStoragePort.downloadByKey(existingDoc.getOriginalDocPath());
        }
        try {
            return documentDownloadPort.download(command.getPdfUrl());
        } catch (Exception e) {
            log.error("Phase 2: Failed to download PDF from {}", command.getPdfUrl(), e);
            return null;
        }
    }

    private void tryDelete(String key) {
        try {
            documentStoragePort.delete(key);
        } catch (Exception e) {
            log.warn("Compensation: failed to delete S3 key {} — continuing", key, e);
        }
    }

    private String buildOriginalKey(SigningFormat format, String documentId, int retryCount) {
        return String.format("%s/%s/attempt-%d/original.%s",
                format.name(), documentId, retryCount, format.fileExtension());
    }

    private void setupMdc(String sagaId, String correlationId, String documentId, String documentNumber) {
        MDC.put("sagaId", sagaId);
        MDC.put("correlationId", correlationId);
        if (documentId != null) MDC.put("documentId", documentId);
        if (documentNumber != null) MDC.put("documentNumber", documentNumber);
    }
}
