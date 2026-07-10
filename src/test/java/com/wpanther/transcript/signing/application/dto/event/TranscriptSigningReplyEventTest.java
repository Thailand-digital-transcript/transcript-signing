package com.wpanther.transcript.signing.application.dto.event;

import com.wpanther.transcript.saga.domain.enums.ReplyStatus;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptSigningReplyEventTest {

    @Test
    void success_setsUrlSizeAndFormat() {
        TranscriptSigningReplyEvent reply = TranscriptSigningReplyEvent.success(
                "saga-1", SagaStep.SIGN_XML, "corr-1", "http://minio/signed.xml", 1234L,
                SigningFormat.XML);

        assertThat(reply.getSagaId()).isEqualTo("saga-1");
        assertThat(reply.getSagaStep()).isEqualTo(SagaStep.SIGN_XML);
        assertThat(reply.getCorrelationId()).isEqualTo("corr-1");
        assertThat(reply.getStatus()).isEqualTo(ReplyStatus.SUCCESS);
        assertThat(reply.getSignedDocUrl()).isEqualTo("http://minio/signed.xml");
        assertThat(reply.getSignedDocSize()).isEqualTo(1234L);
        assertThat(reply.getFormat()).isEqualTo(SigningFormat.XML);
    }

    @Test
    void failure_carriesErrorMessage() {
        TranscriptSigningReplyEvent reply = TranscriptSigningReplyEvent.failure(
                "saga-1", SagaStep.SIGN_PDF, "corr-1", "CSC timeout");

        assertThat(reply.getStatus()).isEqualTo(ReplyStatus.FAILURE);
        assertThat(reply.getErrorMessage()).isEqualTo("CSC timeout");
    }

    @Test
    void compensated_returnsCompensatedStatus() {
        TranscriptSigningReplyEvent reply = TranscriptSigningReplyEvent.compensated(
                "saga-1", SagaStep.SIGN_XML, "corr-1");

        assertThat(reply.getStatus()).isEqualTo(ReplyStatus.COMPENSATED);
        assertThat(reply.getSagaId()).isEqualTo("saga-1");
    }
}
