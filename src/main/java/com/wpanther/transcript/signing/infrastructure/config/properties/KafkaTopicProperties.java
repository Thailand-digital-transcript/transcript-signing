package com.wpanther.transcript.signing.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.kafka.topics")
@Getter
@Setter
public class KafkaTopicProperties {

    private String sagaCommandTranscriptSigningBatch = "saga.command.transcript-signing.batch";
    private String sagaReplyTranscriptSigning     = "saga.reply.transcript-signing";
    private String dlq                            = "transcript.signing.dlq";
}
