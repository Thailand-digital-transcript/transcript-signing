package com.wpanther.transcript.signing.infrastructure.persistence;

import com.wpanther.transcript.signing.domain.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BatchSigningJobMapper {

    public BatchSigningJobEntity toEntity(BatchSigningJob job) {
        BatchSigningJobEntity e = new BatchSigningJobEntity();
        e.setId(job.getId());
        e.setCorrelationId(job.getCorrelationId());
        e.setBatchId(job.getBatchId());
        e.setSagaId(job.getSagaId());
        e.setSignerRole(job.getSignerRole().name());
        e.setFormat(job.getFormat().name());
        e.setStatus(job.getStatus().name());
        e.setCreatedAt(job.getCreatedAt());
        e.setCompletedAt(job.getCompletedAt());
        e.setVersion(job.getVersion());
        e.setItems(job.getItems().stream().map(this::toItemEntity).toList());
        return e;
    }

    private BatchSigningItemEntity toItemEntity(BatchSigningItem i) {
        BatchSigningItemEntity e = new BatchSigningItemEntity();
        e.setId(i.getId());
        e.setDocumentId(i.getDocumentId());
        e.setDocumentNumber(i.getDocumentNumber());
        e.setSourceStorageKey(i.getSourceStorageKey());
        e.setStatus(i.getStatus().name());
        e.setSigId(i.getSigId());
        e.setSigningTime(i.getSigningTime());
        e.setPendingSignature(i.getPendingSignature());
        e.setSignedDocKey(i.getSignedDocKey());
        e.setSignedDocUrl(i.getSignedDocUrl());
        e.setSignedDocSize(i.getSignedDocSize());
        e.setErrorMessage(i.getErrorMessage());
        return e;
    }

    public BatchSigningJob toDomain(BatchSigningJobEntity e) {
        List<BatchSigningItem> items = e.getItems().stream().map(this::toItemDomain).toList();
        return BatchSigningJob.rehydrate(e.getId(), e.getCorrelationId(), e.getBatchId(), e.getSagaId(),
                SignerRole.valueOf(e.getSignerRole()), SigningFormat.valueOf(e.getFormat()),
                BatchJobStatus.valueOf(e.getStatus()), items, e.getVersion());
    }

    private BatchSigningItem toItemDomain(BatchSigningItemEntity e) {
        return BatchSigningItem.rehydrate(e.getId(), e.getDocumentId(), e.getDocumentNumber(),
                e.getSourceStorageKey(), BatchItemStatus.valueOf(e.getStatus()), e.getSigId(),
                e.getSigningTime(), e.getPendingSignature(), e.getSignedDocKey(), e.getSignedDocUrl(),
                e.getErrorMessage(), e.getSignedDocSize());
    }
}
