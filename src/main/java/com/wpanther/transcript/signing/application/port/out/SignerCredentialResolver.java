package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.domain.model.SignerRole;

public interface SignerCredentialResolver {

    ResolvedSigner resolve(SignerRole role);

    /** Everything the batch handler needs to drive a CSC multi-hash sign for one signer role. */
    record ResolvedSigner(String credentialId, String pin, String certificatePem,
                          String hashAlgorithmOid) {}
}
