package com.wpanther.transcript.signing.application.port.out;

import java.util.List;

public interface CscSignaturePort {
    /** Existing: sign a single hash. */
    String signHash(String hashBase64, String sadToken, String credentialId, String hashAlgorithmOid);

    /** Batch: sign N hashes; returns N signatures index-aligned to the inputs. */
    List<String> signHash(List<String> hashesBase64, String sadToken, String credentialId,
                          String hashAlgorithmOid);
}
