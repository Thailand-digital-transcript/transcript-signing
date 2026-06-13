package com.wpanther.transcript.signing.domain.model;

import java.time.Instant;

public class SignedTranscriptDocument {

    private final SignedTranscriptDocumentId id;
    private final String documentId;
    private final String documentNumber;
    private final SigningFormat format;

    private String originalDocPath;
    private String originalDocUrl;
    private long originalDocSize;

    private String signedDocPath;
    private String signedDocUrl;
    private long signedDocSize;

    private String transactionId;
    private String pendingSignature;
    private String certificate;
    private String signatureLevel;
    private Instant signatureTimestamp;

    private SigningStatus status;
    private String errorMessage;
    private int retryCount;

    private final Instant createdAt;
    private Instant completedAt;

    private Long version;

    private SignedTranscriptDocument(SignedTranscriptDocumentId id, String documentId,
                                      String documentNumber, SigningFormat format,
                                      String originalDocPath, String originalDocUrl,
                                      long originalDocSize, SigningStatus status, Instant createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.format = format;
        this.originalDocPath = originalDocPath;
        this.originalDocUrl = originalDocUrl;
        this.originalDocSize = originalDocSize;
        this.status = status;
        this.createdAt = createdAt;
        this.retryCount = 0;
    }

    public static SignedTranscriptDocument create(String documentId, String documentNumber,
                                                   SigningFormat format, String originalDocPath,
                                                   String originalDocUrl, long originalDocSize) {
        return new SignedTranscriptDocument(
                SignedTranscriptDocumentId.generate(), documentId, documentNumber, format,
                originalDocPath, originalDocUrl, originalDocSize,
                SigningStatus.PENDING, Instant.now());
    }

    public void startSigning() {
        if (status != SigningStatus.PENDING && status != SigningStatus.FAILED) {
            throw new SigningException("INVALID_STATE_TRANSITION",
                    "Cannot start signing from state: " + status);
        }
        this.status = SigningStatus.SIGNING;
    }

    public void markCompleted(String signedDocPath, String signedDocUrl, long signedDocSize,
                               String signatureLevel, Instant signatureTimestamp) {
        if (status != SigningStatus.SIGNING) {
            throw new SigningException("INVALID_STATE_TRANSITION",
                    "Cannot complete from state: " + status);
        }
        this.signedDocPath = signedDocPath;
        this.signedDocUrl = signedDocUrl;
        this.signedDocSize = signedDocSize;
        this.signatureLevel = signatureLevel;
        this.signatureTimestamp = signatureTimestamp;
        this.pendingSignature = null;
        this.status = SigningStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = SigningStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount++;
    }

    public void saveTransactionCheckpoint(String transactionId, String pendingSignature,
                                           String certificate) {
        this.transactionId = transactionId;
        this.pendingSignature = pendingSignature;
        this.certificate = certificate;
    }

    public boolean isMaxRetriesExceeded(int maxRetries) {
        return retryCount >= maxRetries;
    }

    public SignedTranscriptDocumentId getId()         { return id; }
    public String getDocumentId()                     { return documentId; }
    public String getDocumentNumber()                 { return documentNumber; }
    public SigningFormat getFormat()                  { return format; }
    public String getOriginalDocPath()                { return originalDocPath; }
    public String getOriginalDocUrl()                 { return originalDocUrl; }
    public long getOriginalDocSize()                  { return originalDocSize; }
    public String getSignedDocPath()                  { return signedDocPath; }
    public String getSignedDocUrl()                   { return signedDocUrl; }
    public long getSignedDocSize()                    { return signedDocSize; }
    public String getTransactionId()                  { return transactionId; }
    public String getPendingSignature()               { return pendingSignature; }
    public String getCertificate()                    { return certificate; }
    public String getSignatureLevel()                 { return signatureLevel; }
    public Instant getSignatureTimestamp()            { return signatureTimestamp; }
    public SigningStatus getStatus()                  { return status; }
    public String getErrorMessage()                   { return errorMessage; }
    public int getRetryCount()                        { return retryCount; }
    public Instant getCreatedAt()                     { return createdAt; }
    public Instant getCompletedAt()                   { return completedAt; }
    public Long getVersion()                          { return version; }
    public void setVersion(Long version)              { this.version = version; }
}
