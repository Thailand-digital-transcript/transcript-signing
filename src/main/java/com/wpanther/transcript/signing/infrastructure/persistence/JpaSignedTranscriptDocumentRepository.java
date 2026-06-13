package com.wpanther.transcript.signing.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface JpaSignedTranscriptDocumentRepository
        extends JpaRepository<SignedTranscriptDocumentEntity, UUID> {

    Optional<SignedTranscriptDocumentEntity> findByDocumentId(String documentId);

    boolean existsByDocumentId(String documentId);

    @Modifying
    @Query("DELETE FROM SignedTranscriptDocumentEntity e WHERE e.id = :id")
    void deleteById(UUID id);
}
