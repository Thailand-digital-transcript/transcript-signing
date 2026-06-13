package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.domain.model.SigningFormat;

public interface SagaReplyPort {
    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                        String signedDocUrl, long signedDocSize, SigningFormat format);
    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage);
    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}
