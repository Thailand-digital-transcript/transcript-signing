package com.wpanther.transcript.signing.infrastructure.persistence;

import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocument;
import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocumentId;
import org.springframework.stereotype.Component;

@Component
public class SignedTranscriptDocumentMapper {

    public SignedTranscriptDocument toDomain(SignedTranscriptDocumentEntity entity) {
        return SignedTranscriptDocument.rehydrate(
                new SignedTranscriptDocumentId(entity.getId()),
                entity.getDocumentId(),
                entity.getDocumentNumber(),
                entity.getFormat(),
                entity.getOriginalDocPath(),
                entity.getOriginalDocUrl(),
                entity.getOriginalDocSize(),
                entity.getSignedDocPath(),
                entity.getSignedDocUrl(),
                entity.getSignedDocSize(),
                entity.getTransactionId(),
                entity.getPendingSignature(),
                entity.getCertificate(),
                entity.getSignatureLevel(),
                entity.getSignatureTimestamp(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getRetryCount(),
                entity.getCreatedAt(),
                entity.getCompletedAt(),
                entity.getVersion(),
                entity.getSigId(),
                entity.getSigningTime());
    }

    public SignedTranscriptDocumentEntity toEntity(SignedTranscriptDocument domain) {
        SignedTranscriptDocumentEntity entity = new SignedTranscriptDocumentEntity();
        entity.setId(domain.getId().value());
        entity.setDocumentId(domain.getDocumentId());
        entity.setDocumentNumber(domain.getDocumentNumber());
        entity.setFormat(domain.getFormat());
        entity.setOriginalDocPath(domain.getOriginalDocPath());
        entity.setOriginalDocUrl(domain.getOriginalDocUrl());
        entity.setOriginalDocSize(domain.getOriginalDocSize());
        entity.setSignedDocPath(domain.getSignedDocPath());
        entity.setSignedDocUrl(domain.getSignedDocUrl());
        entity.setSignedDocSize(domain.getSignedDocSize());
        entity.setTransactionId(domain.getTransactionId());
        entity.setPendingSignature(domain.getPendingSignature());
        entity.setCertificate(domain.getCertificate());
        entity.setSigId(domain.getSigId());
        entity.setSigningTime(domain.getSigningTime());
        entity.setSignatureLevel(domain.getSignatureLevel());
        entity.setSignatureTimestamp(domain.getSignatureTimestamp());
        entity.setStatus(domain.getStatus());
        entity.setErrorMessage(domain.getErrorMessage());
        entity.setRetryCount(domain.getRetryCount());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setCompletedAt(domain.getCompletedAt());
        entity.setVersion(domain.getVersion());
        return entity;
    }
}
