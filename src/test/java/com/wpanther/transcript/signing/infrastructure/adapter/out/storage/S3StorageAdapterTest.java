package com.wpanther.transcript.signing.infrastructure.adapter.out.storage;

import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageAdapterTest {

    @Mock S3Client s3Client;
    @Mock S3Presigner presigner;

    S3StorageAdapter adapter;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setBucketName("test-bucket");
        props.setEndpoint("http://localhost:9001");
        props.setAccessKey("minioadmin");
        props.setSecretKey("minioadmin");
        props.setRegion("us-east-1");
        props.setPathStyleAccess(true);

        adapter = new S3StorageAdapter(props, s3Client, presigner);
    }

    @Test
    void upload_callsS3PutObjectAndReturnsStorageResult() throws Exception {
        byte[] content = "hello".getBytes();
        String key = "XML/doc-001/attempt-0/signed.xml";

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://localhost:9001/test-bucket/" + key));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        StorageResult result = adapter.upload(content, key);

        assertThat(result.path()).isEqualTo(key);
        assertThat(result.url()).contains(key);
        assertThat(result.size()).isEqualTo(content.length);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_s3Failure_throwsSigningException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("bucket not found").build());

        assertThatThrownBy(() -> adapter.upload("data".getBytes(), "XML/doc-001/attempt-0/original.xml"))
                .isInstanceOf(SigningException.class);
    }

    @Test
    void delete_callsS3DeleteObject() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        adapter.delete("XML/doc-001/attempt-0/original.xml");

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }
}
