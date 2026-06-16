package com.wpanther.transcript.signing.integration.support;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;

/**
 * Helper for asserting on S3/MinIO state during integration tests.
 * The transcript-signing service uses keys under the {@code transcripts} bucket
 * (configured in application.yml as {@code app.storage.bucket-name}).
 */
public class MinioTestHelper {

    private final S3Client s3;
    private final String bucket;

    public MinioTestHelper(String endpoint, String accessKey, String secretKey, String bucket) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
    }

    /** Returns true if an object exists at {@code key} in the configured bucket. */
    public boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            // Treat any other failure (e.g. 404 mapped to a different exception)
            // as "not present" so the test can assert a negative.
            return false;
        }
    }

    /**
     * Downloads the object at {@code key} and returns its raw bytes. Caller is responsible
     * for decoding (e.g. UTF-8 for XML) — this helper returns the bytes unchanged.
     */
    public byte[] getObjectBytes(String key) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(), ResponseTransformer.toBytes()).asByteArray();
    }
}

