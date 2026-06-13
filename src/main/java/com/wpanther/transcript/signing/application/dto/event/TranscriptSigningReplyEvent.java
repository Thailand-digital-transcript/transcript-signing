package com.wpanther.transcript.signing.application.dto.event;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaReply;
import com.wpanther.transcript.signing.domain.model.SigningFormat;

public class TranscriptSigningReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    private String signedDocUrl;
    private Long signedDocSize;
    private SigningFormat format;

    public static TranscriptSigningReplyEvent success(String sagaId, SagaStep sagaStep,
                                                       String correlationId, String signedDocUrl,
                                                       long signedDocSize, SigningFormat format) {
        TranscriptSigningReplyEvent reply =
                new TranscriptSigningReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS);
        reply.signedDocUrl = signedDocUrl;
        reply.signedDocSize = signedDocSize;
        reply.format = format;
        return reply;
    }

    public static TranscriptSigningReplyEvent failure(String sagaId, SagaStep sagaStep,
                                                       String correlationId, String errorMessage) {
        return new TranscriptSigningReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static TranscriptSigningReplyEvent compensated(String sagaId, SagaStep sagaStep,
                                                           String correlationId) {
        return new TranscriptSigningReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private TranscriptSigningReplyEvent(String sagaId, SagaStep sagaStep,
                                         String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    private TranscriptSigningReplyEvent(String sagaId, SagaStep sagaStep,
                                         String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
    }

    public String getSignedDocUrl()  { return signedDocUrl; }
    public Long getSignedDocSize()   { return signedDocSize; }
    public SigningFormat getFormat() { return format; }
}
