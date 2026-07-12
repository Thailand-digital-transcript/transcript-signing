package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.domain.model.StorageRef;

public interface DocumentStoragePort {
    StorageResult upload(byte[] content, String key);
    byte[] downloadByKey(String key);
    /** Reads a bucket-qualified reference. The bucket is checked against an allow-list. */
    byte[] download(StorageRef ref);
    void delete(String key);
}
