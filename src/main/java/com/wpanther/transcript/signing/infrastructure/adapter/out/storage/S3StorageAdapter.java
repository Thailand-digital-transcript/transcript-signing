package com.wpanther.transcript.signing.infrastructure.adapter.out.storage;

import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.port.out.DocumentStoragePort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import com.wpanther.transcript.signing.domain.model.StorageRef;
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

@Slf4j
@Component
public class S3StorageAdapter implements DocumentStoragePort {

    private final StorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    @Autowired
    public S3StorageAdapter(StorageProperties properties) {
        this.properties = properties;
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
        var builder = S3Client.builder()
                .credentialsProvider(credentials)
                .region(Region.of(properties.getRegion()));
        var presignerBuilder = S3Presigner.builder()
                .credentialsProvider(credentials)
                .region(Region.of(properties.getRegion()));
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            URI endpoint = URI.create(properties.getEndpoint());
            builder.endpointOverride(endpoint).forcePathStyle(properties.isPathStyleAccess());
            presignerBuilder.endpointOverride(endpoint);
        }
        this.s3Client = builder.build();
        this.presigner = presignerBuilder.build();
        log.info("S3StorageAdapter initialized for bucket={}", properties.getBucketName());
    }

    // Test constructor — allows injecting mocked S3Client/Presigner
    S3StorageAdapter(StorageProperties properties, S3Client s3Client, S3Presigner presigner) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.presigner = presigner;
    }

    // Test constructor — allow-list tests only exercise download(StorageRef), which never
    // touches the presigner.
    S3StorageAdapter(StorageProperties properties, S3Client s3Client) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.presigner = null;
    }

    @Override
    public StorageResult upload(byte[] content, String key) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(properties.getBucketName())
                    .key(key)
                    .contentLength((long) content.length)
                    .build(), RequestBody.fromBytes(content));
            String url = buildPresignedUrl(key);
            log.debug("Uploaded {} bytes to s3://{}/{}", content.length, properties.getBucketName(), key);
            return new StorageResult(key, url, content.length);
        } catch (S3Exception e) {
            throw new SigningException("S3_UPLOAD_FAILED", "S3 upload failed for key: " + key, e);
        }
    }

    @Override
    public byte[] downloadByKey(String key) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.getBucketName())
                    .key(key)
                    .build()).asByteArray();
        } catch (S3Exception e) {
            throw new SigningException("S3_DOWNLOAD_FAILED", "S3 download failed for key: " + key, e);
        }
    }

    @Override
    public byte[] download(StorageRef ref) {
        if (!properties.getAllowedSourceBuckets().contains(ref.bucket())) {
            // PERMANENT failure — do not retry. A disallowed bucket means a bug or a
            // compromise upstream, and no number of redeliveries will change that.
            throw new SigningException("STORAGE_BUCKET_NOT_ALLOWED",
                    "'" + ref.bucket() + "' is not an allowed source bucket");
        }
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(ref.bucket()).key(ref.key()).build()).asByteArray();
        } catch (Exception e) {
            throw new SigningException("STORAGE_DOWNLOAD_FAILED",
                    "Failed to download s3://" + ref.bucket() + "/" + ref.key(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucketName())
                    .key(key)
                    .build());
            log.debug("Deleted s3://{}/{}", properties.getBucketName(), key);
        } catch (S3Exception e) {
            throw new SigningException("S3_DELETE_FAILED", "S3 delete failed for key: " + key, e);
        }
    }

    private String buildPresignedUrl(String key) {
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(properties.getPresignedUrlTtlMinutes()))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(properties.getBucketName())
                            .key(key)
                            .build())
                    .build()).url().toString();
        } catch (Exception e) {
            log.warn("Failed to generate presigned URL for key {}", key, e);
            return String.format("%s/%s/%s", properties.getEndpoint(),
                    properties.getBucketName(), key);
        }
    }
}
