package com.wpanther.transcript.signing.domain.model;

import java.time.Instant;
import java.util.UUID;

public class BatchSigningItem {

    private final UUID id;
    private final String documentId;
    private final String documentNumber;
    private final String sourceStorageKey;
    private final String sourceBucket;      // NEW — the source's bucket; null = signing's own bucket
    private final String targetStorageKey;  // NEW — where to write the signed output

    private BatchItemStatus status;
    private String sigId;
    private Instant signingTime;
    private String pendingSignature;   // set at the per-item TX1.5 checkpoint
    private String signedDocKey;
    private String signedDocUrl;
    private Long signedDocSize;
    private String errorMessage;

    private BatchSigningItem(UUID id, String documentId, String documentNumber,
                             String sourceStorageKey, String sourceBucket, String targetStorageKey,
                             BatchItemStatus status) {
        this.id = id;
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.sourceStorageKey = sourceStorageKey;
        this.sourceBucket = sourceBucket;
        this.targetStorageKey = targetStorageKey;
        this.status = status;
    }

    public static BatchSigningItem create(String documentId, String documentNumber, String storageKey,
                                          String sourceBucket, String targetStorageKey) {
        return new BatchSigningItem(UUID.randomUUID(), documentId, documentNumber, storageKey,
                sourceBucket, targetStorageKey, BatchItemStatus.PENDING);
    }

    public static BatchSigningItem rehydrate(UUID id, String documentId, String documentNumber,
                                             String sourceStorageKey, String sourceBucket,
                                             String targetStorageKey, BatchItemStatus status,
                                             String sigId, Instant signingTime, String pendingSignature,
                                             String signedDocKey, String signedDocUrl, String errorMessage,
                                             Long signedDocSize) {
        BatchSigningItem i = new BatchSigningItem(id, documentId, documentNumber, sourceStorageKey,
                sourceBucket, targetStorageKey, status);
        i.sigId = sigId;
        i.signingTime = signingTime;
        i.pendingSignature = pendingSignature;
        i.signedDocKey = signedDocKey;
        i.signedDocUrl = signedDocUrl;
        i.errorMessage = errorMessage;
        i.signedDocSize = signedDocSize;
        return i;
    }

    public boolean isSigned()        { return status == BatchItemStatus.SIGNED; }
    public boolean hasSignature()    { return pendingSignature != null; }

    /** Per-item TX1.5 checkpoint: store the CSC signature + the deterministic prepare inputs. */
    public void checkpoint(String sigId, Instant signingTime, String pendingSignature) {
        this.sigId = sigId;
        this.signingTime = signingTime;
        this.pendingSignature = pendingSignature;
    }

    public void markSigned(String signedDocKey, String signedDocUrl, long signedDocSize) {
        this.signedDocKey = signedDocKey;
        this.signedDocUrl = signedDocUrl;
        this.signedDocSize = signedDocSize;
        this.errorMessage = null;
        this.status = BatchItemStatus.SIGNED;
    }

    public void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = BatchItemStatus.FAILED;
    }

    public UUID getId()                 { return id; }
    public String getDocumentId()       { return documentId; }
    public String getDocumentNumber()   { return documentNumber; }
    public String getSourceStorageKey() { return sourceStorageKey; }
    public String getSourceBucket()     { return sourceBucket; }
    public String getTargetStorageKey() { return targetStorageKey; }
    public BatchItemStatus getStatus()  { return status; }
    public String getSigId()            { return sigId; }
    public Instant getSigningTime()     { return signingTime; }
    public String getPendingSignature() { return pendingSignature; }
    public String getSignedDocKey()     { return signedDocKey; }
    public String getSignedDocUrl()     { return signedDocUrl; }
    public Long getSignedDocSize()      { return signedDocSize; }
    public String getErrorMessage()     { return errorMessage; }
}
