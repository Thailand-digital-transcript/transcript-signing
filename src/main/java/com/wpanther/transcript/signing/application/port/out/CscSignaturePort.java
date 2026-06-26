package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.application.dto.CscSignatureResult;

import java.util.List;

public interface CscSignaturePort {
    /** Sign a single hash; returns the signature and CSC transaction id. */
    CscSignatureResult signHash(String hashBase64, String sadToken, String credentialId, String hashAlgorithmOid);

    /** Batch: sign N hashes; returns N signatures index-aligned to the inputs plus the CSC transaction id. */
    CscSignatureResult signHash(List<String> hashesBase64, String sadToken, String credentialId,
                          String hashAlgorithmOid);
}
