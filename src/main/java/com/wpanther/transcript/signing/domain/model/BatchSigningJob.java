package com.wpanther.transcript.signing.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class BatchSigningJob {

    private final UUID id;
    private final String correlationId;
    private final String batchId;
    private final String sagaId;
    private final SignerRole signerRole;
    private final SigningFormat format;
    private final List<BatchSigningItem> items;

    private BatchJobStatus status;
    private final Instant createdAt;
    private Instant completedAt;
    private Long version;

    private BatchSigningJob(UUID id, String correlationId, String batchId, String sagaId,
                            SignerRole signerRole, SigningFormat format, BatchJobStatus status,
                            List<BatchSigningItem> items, Instant createdAt) {
        this.id = id;
        this.correlationId = correlationId;
        this.batchId = batchId;
        this.sagaId = sagaId;
        this.signerRole = signerRole;
        this.format = format;
        this.status = status;
        this.items = items;
        this.createdAt = createdAt;
    }

    public static BatchSigningJob create(String correlationId, String batchId, String sagaId,
                                         SignerRole signerRole, SigningFormat format,
                                         List<BatchSigningItem> items) {
        return new BatchSigningJob(UUID.randomUUID(), correlationId, batchId, sagaId, signerRole,
                format, BatchJobStatus.PENDING, items, Instant.now());
    }

    public static BatchSigningJob rehydrate(UUID id, String correlationId, String batchId, String sagaId,
                                            SignerRole signerRole, SigningFormat format,
                                            BatchJobStatus status, List<BatchSigningItem> items,
                                            Long version) {
        BatchSigningJob job = new BatchSigningJob(id, correlationId, batchId, sagaId, signerRole,
                format, status, items, Instant.now());
        job.version = version;
        return job;
    }

    public void startSigning() { this.status = BatchJobStatus.SIGNING; }

    /** Items still to be signed (not SIGNED) that have no stored signature yet → need a CSC call. */
    public List<BatchSigningItem> itemsNeedingFreshSignature() {
        return items.stream().filter(i -> !i.isSigned() && !i.hasSignature()).toList();
    }

    /** Items not yet SIGNED → need an embed pass (after they have a signature). */
    public List<BatchSigningItem> itemsNeedingEmbed() {
        return items.stream().filter(i -> !i.isSigned()).toList();
    }

    public boolean allItemsSigned() {
        return items.stream().allMatch(BatchSigningItem::isSigned);
    }

    public boolean anyItemSigned() {
        return items.stream().anyMatch(BatchSigningItem::isSigned);
    }

    public void finish() {
        this.status = allItemsSigned() ? BatchJobStatus.COMPLETED : BatchJobStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public UUID getId()                    { return id; }
    public String getCorrelationId()       { return correlationId; }
    public String getBatchId()             { return batchId; }
    public String getSagaId()              { return sagaId; }
    public SignerRole getSignerRole()      { return signerRole; }
    public SigningFormat getFormat()       { return format; }
    public List<BatchSigningItem> getItems() { return items; }
    public BatchJobStatus getStatus()      { return status; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getCompletedAt()        { return completedAt; }
    public Long getVersion()               { return version; }
    public void setVersion(Long version)   { this.version = version; }
}
