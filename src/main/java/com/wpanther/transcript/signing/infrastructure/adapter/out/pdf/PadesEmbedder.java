package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import com.wpanther.transcript.signing.domain.model.SigningException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Slf4j
@Component
public class PadesEmbedder {

    private static final COSName SUBFILTER = COSName.getPDFName("ETSI.CAdES.detached");

    // PadesDigestComputer returns the original input PDF as preparedPdfBytes (PDFBox
    // 3.0.6 buffers the actual incremental update internally and only writes to the
    // output stream when setSignature is called). We re-add the signature here with
    // the same FIXED_DOCUMENT_ID the digest phase used, so the resulting byte range
    // and document digest are identical and the CMS signature produced by CSC
    // remains valid for the final signed PDF.
    public byte[] embed(byte[] preparedPdfBytes, byte[] cmsSignature) {
        try (PDDocument document = Loader.loadPDF(preparedPdfBytes)) {
            document.setDocumentId(PadesDigestComputer.FIXED_DOCUMENT_ID);

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(SUBFILTER);
            // MUST mirror PadesDigestComputer's signature dict byte-for-byte: /Name is
            // inside the signed byte range, so omitting it here changes the document
            // digest and the CSC signature (made over the digest-phase bytes) no longer
            // verifies — a PAdES "Digest Mismatch".
            signature.setName("Transcript Signing Service");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.addSignature(signature);
            ExternalSigningSupport externalSigning =
                    document.saveIncrementalForExternalSigning(out);
            externalSigning.setSignature(cmsSignature);
            return out.toByteArray();
        } catch (Exception e) {
            throw new SigningException("PADES_EMBED_FAILED", "PAdES signature embedding failed", e);
        }
    }
}
