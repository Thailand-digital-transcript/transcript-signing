package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningReplyEvent.ItemResult;

import java.util.List;

public interface BatchSagaReplyPort {
    void publishBatchReply(String sagaId, SagaStep sagaStep, String correlationId, String batchId,
                           boolean allSucceeded, List<ItemResult> items);
}
