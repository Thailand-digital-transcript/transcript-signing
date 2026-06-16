package com.wpanther.transcript.signing.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaBatchSigningJobRepository extends JpaRepository<BatchSigningJobEntity, UUID> {
    Optional<BatchSigningJobEntity> findByCorrelationId(String correlationId);
}
