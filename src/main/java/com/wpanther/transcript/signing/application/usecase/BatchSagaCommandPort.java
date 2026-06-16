package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;

public interface BatchSagaCommandPort {
    void handleBatchSigning(BatchSigningCommand command);
}
