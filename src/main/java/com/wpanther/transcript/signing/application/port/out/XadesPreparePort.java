package com.wpanther.transcript.signing.application.port.out;

import com.wpanther.transcript.signing.application.dto.XadesPreparation;

import java.time.Instant;

/**
 * Two-pass XAdES signing for remote (CSC) keys. {@code prepare} is deterministic in
 * (xmlBytes, certificatePem, signingTime, sigId) so that {@code embed} can reproduce the exact
 * bytes that were signed after a crash/restart between the two passes.
 */
public interface XadesPreparePort {

    XadesPreparation prepare(byte[] xmlBytes, String certificatePem, Instant signingTime, String sigId);

    /** Re-runs prepare deterministically and injects the CSC signature value, returning final XML. */
    byte[] embed(byte[] xmlBytes, String certificatePem, Instant signingTime, String sigId,
                 String signatureValueBase64);
}
