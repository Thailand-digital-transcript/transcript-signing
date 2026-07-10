package com.wpanther.transcript.signing.application.dto.event;

import com.wpanther.transcript.signing.domain.model.SigningFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptSignedEventTest {

    @Test
    void constructor_andGetters_returnAllFields() {
        Instant occurred = Instant.parse("2026-06-16T10:00:00Z");
        Instant sigTime = Instant.parse("2026-06-16T10:00:01Z");
        TranscriptSignedEvent event = new TranscriptSignedEvent(
                "evt-1", occurred, "doc-1", "num-1", SigningFormat.XML,
                "http://minio/signed.xml", "XAdES-BASELINE-B", sigTime);

        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getOccurredAt()).isEqualTo(occurred);
        assertThat(event.getDocumentId()).isEqualTo("doc-1");
        assertThat(event.getDocumentNumber()).isEqualTo("num-1");
        assertThat(event.getFormat()).isEqualTo(SigningFormat.XML);
        assertThat(event.getSignedDocUrl()).isEqualTo("http://minio/signed.xml");
        assertThat(event.getSignatureLevel()).isEqualTo("XAdES-BASELINE-B");
        assertThat(event.getSignatureTimestamp()).isEqualTo(sigTime);
    }

    @Test
    void of_buildsEventWithGeneratedIdAndNow() {
        Instant sigTime = Instant.parse("2026-06-16T10:00:01Z");
        TranscriptSignedEvent event = TranscriptSignedEvent.of(
                "doc-1", "num-1", SigningFormat.PDF, "http://minio/signed.pdf",
                "PAdES-BASELINE-B", sigTime);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getDocumentId()).isEqualTo("doc-1");
        assertThat(event.getDocumentNumber()).isEqualTo("num-1");
        assertThat(event.getFormat()).isEqualTo(SigningFormat.PDF);
        assertThat(event.getSignedDocUrl()).isEqualTo("http://minio/signed.pdf");
        assertThat(event.getSignatureLevel()).isEqualTo("PAdES-BASELINE-B");
        assertThat(event.getSignatureTimestamp()).isEqualTo(sigTime);
        assertThat(event.getOccurredAt()).isNotNull();
    }
}
