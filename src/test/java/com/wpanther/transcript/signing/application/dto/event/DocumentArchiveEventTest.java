package com.wpanther.transcript.signing.application.dto.event;

import com.wpanther.transcript.signing.domain.model.SigningFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentArchiveEventTest {

    @Test
    void constructor_andGetters_returnAllFields() {
        Instant occurred = Instant.parse("2026-06-16T10:00:00Z");
        Instant signedAt = Instant.parse("2026-06-16T10:00:02Z");
        DocumentArchiveEvent event = new DocumentArchiveEvent(
                "evt-1", occurred, "doc-1", "num-1", SigningFormat.XML,
                "XML/doc-1/signed.xml", signedAt);

        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getOccurredAt()).isEqualTo(occurred);
        assertThat(event.getDocumentId()).isEqualTo("doc-1");
        assertThat(event.getDocumentNumber()).isEqualTo("num-1");
        assertThat(event.getFormat()).isEqualTo(SigningFormat.XML);
        assertThat(event.getSignedDocPath()).isEqualTo("XML/doc-1/signed.xml");
        assertThat(event.getSignedAt()).isEqualTo(signedAt);
    }

    @Test
    void of_buildsEventWithGeneratedIdAndNow() {
        Instant signedAt = Instant.parse("2026-06-16T10:00:02Z");
        DocumentArchiveEvent event = DocumentArchiveEvent.of(
                "doc-1", "num-1", SigningFormat.PDF, "PDF/doc-1/signed.pdf", signedAt);

        assertThat(event.getEventId()).isNotBlank();
        assertThat(event.getDocumentId()).isEqualTo("doc-1");
        assertThat(event.getDocumentNumber()).isEqualTo("num-1");
        assertThat(event.getFormat()).isEqualTo(SigningFormat.PDF);
        assertThat(event.getSignedDocPath()).isEqualTo("PDF/doc-1/signed.pdf");
        assertThat(event.getSignedAt()).isEqualTo(signedAt);
        assertThat(event.getOccurredAt()).isNotNull();
    }
}
