package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;

public interface PadesEmbeddingPort {
    PadesDigestResult computeByteRangeDigest(byte[] pdfBytes);
    byte[] embedSignature(PadesDigestResult prepared, String rawSignatureBase64, String certificatePem);
}
