package com.wpanther.transcript.signing.domain.repository;

import com.wpanther.transcript.signing.domain.model.BatchSigningJob;

import java.util.Optional;

public interface BatchSigningJobRepository {
    BatchSigningJob save(BatchSigningJob job);
    Optional<BatchSigningJob> findByCorrelationId(String correlationId);
}
