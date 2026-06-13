package com.wpanther.transcript.signing.infrastructure.persistence;

import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.domain.model.SigningStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signed_transcript_documents",
        indexes = {
            @Index(name = "idx_signed_transcript_document_id", columnList = "document_id", unique = true),
            @Index(name = "idx_signed_transcript_status",      columnList = "status"),
            @Index(name = "idx_signed_transcript_format",      columnList = "format")
        })
@Getter
@Setter
public class SignedTranscriptDocumentEntity {

    @Id
    private UUID id;

    @Column(name = "document_id",     nullable = false, length = 100)
    private String documentId;

    @Column(name = "document_number", nullable = false, length = 50)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10)
    private SigningFormat format;

    @Column(name = "original_doc_path", nullable = false, length = 500)
    private String originalDocPath;

    @Column(name = "original_doc_url", nullable = false, length = 1000)
    private String originalDocUrl;

    @Column(name = "original_doc_size", nullable = false)
    private long originalDocSize;

    @Column(name = "signed_doc_path", length = 500)
    private String signedDocPath;

    @Column(name = "signed_doc_url", length = 1000)
    private String signedDocUrl;

    @Column(name = "signed_doc_size")
    private Long signedDocSize;

    @Column(name = "transaction_id", length = 200)
    private String transactionId;

    @Column(name = "pending_signature", columnDefinition = "TEXT")
    private String pendingSignature;

    @Column(name = "certificate", columnDefinition = "TEXT")
    private String certificate;

    @Column(name = "signature_level", length = 50)
    private String signatureLevel;

    @Column(name = "signature_timestamp")
    private Instant signatureTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SigningStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        updatedAt = Instant.now();
        if (createdAt == null) createdAt = updatedAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
