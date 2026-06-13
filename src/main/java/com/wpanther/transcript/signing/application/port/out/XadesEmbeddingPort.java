package com.wpanther.transcript.signing.application.port.out;

public interface XadesEmbeddingPort {
    byte[] embedSignature(byte[] xmlBytes, String signatureBase64, String certificatePem);
}
