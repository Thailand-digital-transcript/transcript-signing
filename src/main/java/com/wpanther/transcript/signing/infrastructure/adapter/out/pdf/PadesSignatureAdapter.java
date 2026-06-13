package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;
import com.wpanther.transcript.signing.application.port.out.PadesEmbeddingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PadesSignatureAdapter implements PadesEmbeddingPort {

    private final PadesDigestComputer digestComputer;
    private final PadesCmsBuilder cmsBuilder;
    private final PadesEmbedder embedder;

    @Override
    public PadesDigestResult computeByteRangeDigest(byte[] pdfBytes) {
        return digestComputer.computeByteRangeDigest(pdfBytes);
    }

    @Override
    public byte[] embedSignature(PadesDigestResult prepared, String rawSignatureBase64,
                                  String certificatePem) {
        // Pass the pre-built signedAttrs DER verbatim — CSC signed their hash, so they must
        // be embedded byte-for-byte to make the CMS SignerInfo verifiable.
        byte[] cmsSignature = cmsBuilder.buildCmsSignature(
                prepared.encodedSignedAttrs(), rawSignatureBase64, certificatePem);
        return embedder.embed(prepared.preparedPdfBytes(), cmsSignature);
    }
}
