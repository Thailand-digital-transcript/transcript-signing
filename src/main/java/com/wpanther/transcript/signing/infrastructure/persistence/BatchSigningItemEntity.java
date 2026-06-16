package com.wpanther.transcript.signing.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "batch_signing_items")
@Getter @Setter
public class BatchSigningItemEntity {
    @Id private UUID id;
    @Column(name = "document_id", nullable = false, length = 100) private String documentId;
    @Column(name = "document_number", nullable = false, length = 50) private String documentNumber;
    @Column(name = "source_storage_key", nullable = false, length = 500) private String sourceStorageKey;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "sig_id", length = 100) private String sigId;
    @Column(name = "signing_time") private Instant signingTime;
    @Column(name = "pending_signature") private String pendingSignature;
    @Column(name = "signed_doc_key", length = 500) private String signedDocKey;
    @Column(name = "signed_doc_url", length = 1000) private String signedDocUrl;
    @Column(name = "signed_doc_size") private Long signedDocSize;
    @Column(name = "error_message") private String errorMessage;
}
