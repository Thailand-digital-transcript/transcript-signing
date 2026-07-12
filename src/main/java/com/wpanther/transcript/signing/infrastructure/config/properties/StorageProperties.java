package com.wpanther.transcript.signing.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.storage")
@Getter
@Setter
public class StorageProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
    private String region = "us-east-1";
    private boolean pathStyleAccess = true;
    private int presignedUrlTtlMinutes = 60;

    /**
     * Signing decides what it is willing to read. The orchestrator names the bucket, which
     * is a confused-deputy vector: it could otherwise direct signing to read any bucket its
     * credentials reach. Under presigned URLs this boundary was implicit (the orchestrator
     * held the credential); making it explicit is a genuine improvement, not a replacement.
     */
    private List<String> allowedSourceBuckets =
            List.of("transcripts", "signed-transcripts", "transcript-pdfs");
}
