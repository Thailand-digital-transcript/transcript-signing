package com.wpanther.transcript.signing.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;

/**
 * Minimal stub. Task 11 will replace this with the full @ConfigurationProperties-annotated version.
 */
@Getter
@Setter
public class KafkaTopicProperties {

    private String sagaReplyTranscriptSigning;
    private String transcriptSigned;
    private String documentArchive;
}
