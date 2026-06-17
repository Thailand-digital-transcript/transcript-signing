package com.wpanther.transcript.signing.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import com.wpanther.transcript.saga.domain.outbox.OutboxEvent;
import com.wpanther.transcript.saga.domain.outbox.OutboxEventRepository;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningReplyEvent.ItemResult;
import com.wpanther.transcript.signing.infrastructure.config.properties.KafkaTopicProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OutboxBatchSagaReplyAdapterTest {

    final OutboxEventRepository repo = mock(OutboxEventRepository.class);
    final KafkaTopicProperties topics = new KafkaTopicProperties();
    final OutboxBatchSagaReplyAdapter adapter =
            new OutboxBatchSagaReplyAdapter(repo, new ObjectMapper().findAndRegisterModules(), topics);

    @Test
    void buildOutbox_writesEventToReplyTopicPartitionedBySaga() {
        OutboxEvent e = adapter.buildOutbox("saga-1", SagaStep.SIGN_XML, "corr-1", "batch-1", false,
                List.of(ItemResult.signed("d1", "url1", 10L), ItemResult.failed("d2", "boom")));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repo).save(captor.capture());
        OutboxEvent captured = captor.getValue();
        // captured and built e should be the same object since buildOutbox constructed it
        assertThat(captured).isSameAs(e);
        assertThat(captured.getTopic()).isEqualTo(topics.getSagaReplyTranscriptSigning());
        assertThat(captured.getPartitionKey()).isEqualTo("saga-1");
        assertThat(captured.getPayload()).contains("batch-1").contains("FAILED").contains("SIGNED");
    }
}
