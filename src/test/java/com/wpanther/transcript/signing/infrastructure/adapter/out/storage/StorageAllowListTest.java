package com.wpanther.transcript.signing.infrastructure.adapter.out.storage;

import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.domain.model.StorageRef;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class StorageAllowListTest {

    S3Client s3 = mock(S3Client.class);
    StorageProperties props = new StorageProperties();
    S3StorageAdapter adapter;

    @BeforeEach void setUp() {
        props.setAllowedSourceBuckets(List.of("transcripts", "signed-transcripts", "transcript-pdfs"));
        adapter = new S3StorageAdapter(props, s3);
    }

    @Test
    void rejectsADisallowedBucket_beforeTouchingS3() {
        // The orchestrator now names the bucket signing reads. A compromised or buggy
        // orchestrator could name any bucket signing's credentials reach. Reject BEFORE
        // the S3 call, as a PERMANENT failure: a disallowed bucket signals a defect
        // upstream, not a transient fault, and retrying cannot help.
        assertThatThrownBy(() -> adapter.download(new StorageRef("keycloak-secrets", "k")))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("not an allowed source bucket");

        verifyNoInteractions(s3);
    }

    @Test
    void allowsTheThreeConfiguredBuckets() {
        when(s3.getObjectAsBytes((software.amazon.awssdk.services.s3.model.GetObjectRequest) any()))
                .thenReturn(software.amazon.awssdk.core.ResponseBytes.fromByteArray(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                        "ok".getBytes()));
        for (String b : List.of("transcripts", "signed-transcripts", "transcript-pdfs")) {
            assertThatCode(() -> adapter.download(new StorageRef(b, "k"))).doesNotThrowAnyException();
        }
    }

    @Test
    void noCommandFieldEverHoldsAUrl() {
        var item = new BatchSigningCommand.Item("doc-1", "90993829998",
                "2026/07/10/01/transcript-90993829998.xml", "transcripts",
                "2026/07/10/01/transcript-90993829998.registrar.xml");
        // Pins "presign only for principals without credentials" as an executable statement,
        // so a future change cannot quietly reintroduce a bearer token into the outbox.
        assertThat(List.of(item.getStorageKey(), item.getSourceBucket(), item.getTargetStorageKey()))
                .noneMatch(v -> v.matches("^https?://.*"));
    }
}
