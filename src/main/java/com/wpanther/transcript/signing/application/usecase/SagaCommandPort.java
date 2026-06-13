package com.wpanther.transcript.signing.application.usecase;

import com.wpanther.transcript.signing.application.dto.event.CompensateTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;

public interface SagaCommandPort {
    void handleSigningCommand(ProcessTranscriptSigningCommand command);
    void handleCompensationCommand(CompensateTranscriptSigningCommand command);
}
