package com.wpanther.transcript.signing.domain.repository;

import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocument;
import com.wpanther.transcript.signing.domain.model.SignedTranscriptDocumentId;

import java.util.Optional;

public interface SignedTranscriptDocumentRepository {
    SignedTranscriptDocument save(SignedTranscriptDocument document);
    Optional<SignedTranscriptDocument> findById(SignedTranscriptDocumentId id);
    Optional<SignedTranscriptDocument> findByDocumentId(String documentId);
    boolean existsByDocumentId(String documentId);
    void deleteById(SignedTranscriptDocumentId id);
}
