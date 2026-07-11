package com.wpanther.transcript.signing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.domain.model.SignerRole;
import com.wpanther.transcript.signing.domain.model.SigningFormat;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import com.wpanther.transcript.signing.integration.support.IntegrationTestBase;
import com.wpanther.transcript.signing.integration.support.KafkaTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dead-lettered command is only useful if it can be read back and replayed. Camel routes
 * the <em>current</em> exchange body to the DLQ, and by the time a failure surfaces that
 * body is usually the unmarshalled {@link BatchSigningCommand} POJO — which the Kafka
 * StringSerializer renders via {@code toString()}. That produces
 * {@code BatchSigningCommand(super=BatchSigningCommand(eventId=...))}: not JSON, not
 * parseable, not replayable.
 *
 * <p>{@code useOriginalMessage()} sends the bytes we consumed from Kafka instead, so the
 * DLQ record is the original command verbatim — including any fields this service's DTO
 * does not model (it is {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so
 * re-serialising the POJO would silently drop them).
 *
 * <p>The command here carries an empty item list. It unmarshals cleanly and then trips
 * {@code @NotEmpty} in {@code CommandValidator}, so the failure happens with a POJO body —
 * the exact shape of the bug — without touching CSC, the HSM or the database.
 */
class DlqPayloadIT extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTopicProperties topics;

    KafkaTestHelper kafkaHelper;

    @BeforeEach
    void setUp() {
        kafkaHelper = new KafkaTestHelper(KAFKA.getBootstrapServers(), objectMapper);
    }

    @Test
    void deadLetteredCommand_isTheOriginalJson_notAToString() throws Exception {
        String sagaId = "saga-dlq-" + UUID.randomUUID().toString().substring(0, 8);

        var command = new BatchSigningCommand(null, null, null, null,
                sagaId, SagaStep.SIGN_XML, "corr-" + sagaId, "batch-" + sagaId,
                SignerRole.REGISTRAR, SigningFormat.XML,
                List.of());  // empty -> @NotEmpty fails in CommandValidator

        kafkaHelper.sendCommand(topics.getSagaCommandTranscriptSigningBatch(), command);

        // Waiting for the DLQ record is also what keeps this IT from leaking: it is the
        // command's terminal state, so no redelivery is still in flight when we return.
        var record = kafkaHelper.pollFor(topics.getDlq(), Duration.ofSeconds(60), sagaId);
        assertThat(record).as("the failed command must be dead-lettered").isPresent();

        String body = record.get().value();
        assertThat(body)
                .as("DLQ payload must be JSON, not a Lombok toString()")
                .startsWith("{");

        BatchSigningCommand replayed = objectMapper.readValue(body, BatchSigningCommand.class);
        assertThat(replayed.getSagaId()).isEqualTo(sagaId);
        assertThat(replayed.getBatchId()).isEqualTo("batch-" + sagaId);
        assertThat(replayed.getSignerRole()).isEqualTo(SignerRole.REGISTRAR);
        assertThat(replayed.getFormat()).isEqualTo(SigningFormat.XML);
    }
}
