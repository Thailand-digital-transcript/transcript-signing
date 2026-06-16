package com.wpanther.transcript.signing.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "batch_signing_jobs")
@Getter @Setter
public class BatchSigningJobEntity {
    @Id private UUID id;
    @Column(name = "correlation_id", nullable = false, length = 100) private String correlationId;
    @Column(name = "batch_id", nullable = false, length = 100) private String batchId;
    @Column(name = "saga_id", nullable = false, length = 100) private String sagaId;
    @Column(name = "signer_role", nullable = false, length = 20) private String signerRole;
    @Column(nullable = false, length = 10) private String format;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Version private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "job_id", nullable = false)
    private List<BatchSigningItemEntity> items = new ArrayList<>();
}
