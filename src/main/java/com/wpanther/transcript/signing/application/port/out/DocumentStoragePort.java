package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.application.dto.StorageResult;

public interface DocumentStoragePort {
    StorageResult upload(byte[] content, String key);
    byte[] downloadByKey(String key);
    void delete(String key);
}
