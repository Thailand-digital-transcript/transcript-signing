package com.wpanther.transcript.signing.infrastructure.persistence;

import com.wpanther.transcript.signing.domain.model.BatchSigningJob;
import com.wpanther.transcript.signing.domain.repository.BatchSigningJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BatchSigningJobRepositoryAdapter implements BatchSigningJobRepository {

    private final JpaBatchSigningJobRepository jpa;
    private final BatchSigningJobMapper mapper;

    @Override
    public BatchSigningJob save(BatchSigningJob job) {
        // saveAndFlush so the @Version increment is visible across TX1 → TX1.5 → TX2
        // (mirrors the single-doc adapter gotcha in CLAUDE.md).
        var saved = jpa.saveAndFlush(mapper.toEntity(job));
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<BatchSigningJob> findByCorrelationId(String correlationId) {
        return jpa.findByCorrelationId(correlationId).map(mapper::toDomain);
    }
}
