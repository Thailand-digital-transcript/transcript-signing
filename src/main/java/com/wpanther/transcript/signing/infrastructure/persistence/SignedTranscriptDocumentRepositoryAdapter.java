package com.wpanther.transcript.signing.infrastructure.persistence;

import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocument;
import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocumentId;
import com.wpanther.transcript.signing.domain.repository.SignedTranscriptDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SignedTranscriptDocumentRepositoryAdapter
        implements SignedTranscriptDocumentRepository {

    private final JpaSignedTranscriptDocumentRepository jpa;
    private final SignedTranscriptDocumentMapper mapper;

    @Override
    public SignedTranscriptDocument save(SignedTranscriptDocument domain) {
        var entity = mapper.toEntity(domain);
        return mapper.toDomain(jpa.save(entity));
    }

    @Override
    public Optional<SignedTranscriptDocument> findById(SignedTranscriptDocumentId id) {
        return jpa.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<SignedTranscriptDocument> findByDocumentId(String documentId) {
        return jpa.findByDocumentId(documentId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByDocumentId(String documentId) {
        return jpa.existsByDocumentId(documentId);
    }

    @Override
    public void deleteById(SignedTranscriptDocumentId id) {
        jpa.deleteById(id.value());
    }
}
