package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;
import com.wpanther.transcript.signing.domain.model.SigningException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Base64;

@Slf4j
@Component
public class PadesDigestComputer {

    private static final COSName SUBFILTER = COSName.getPDFName("ETSI.CAdES.detached");

    // Fixed document ID so PDFBox's trailer /ID generation (which normally uses
    // System.currentTimeMillis() at save time) is reproducible. Without this, the
    // document digest differs on each call, breaking the TX1.5 retry path where
    // computeByteRangeDigest must produce the same hash as the original attempt
    // (so the same CSC signature remains valid).
    public static final long FIXED_DOCUMENT_ID = 12345L;

    public PadesDigestResult computeByteRangeDigest(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            document.setDocumentId(FIXED_DOCUMENT_ID);

            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(SUBFILTER);
            signature.setName("Transcript Signing Service");
            // signDate is intentionally NOT set: it would be embedded in the
            // signature dict (which is in the signed byte range), making the
            // document digest time-dependent. The signing time can be recorded
            // out-of-band in audit logs; for the signature itself we rely on
            // the CSC-side timestamp.

            ByteArrayOutputStream preparedOut = new ByteArrayOutputStream();
            document.addSignature(signature);
            ExternalSigningSupport externalSigning =
                    document.saveIncrementalForExternalSigning(preparedOut);

            byte[] contentToSign = externalSigning.getContent().readAllBytes();
            byte[] documentDigest = MessageDigest.getInstance("SHA-256").digest(contentToSign);

            // Build CAdES signedAttrs and hash them — CSC signs SHA-256(DER(signedAttrs))
            byte[] encodedSignedAttrs = buildSignedAttrs(documentDigest);
            byte[] signedAttrsDigest = MessageDigest.getInstance("SHA-256").digest(encodedSignedAttrs);
            String signedAttrsDigestBase64 = Base64.getEncoder().encodeToString(signedAttrsDigest);

            // PDFBox 3.0.6 buffers the prepared PDF internally and only writes it
            // out when setSignature is called. The PadesEmbedder re-does the flow
            // (load + addSignature + saveIncrementalForExternalSigning + setSignature)
            // using this same input PDF and the same FIXED_DOCUMENT_ID, so the
            // resulting byte range and document digest match what we hashed here
            // and the CSC signature remains valid.
            byte[] preparedPdfBytes = pdfBytes;
            int[] byteRangeInts = signature.getByteRange();
            long[] byteRange = new long[4];
            for (int i = 0; i < 4; i++) byteRange[i] = byteRangeInts[i];

            return new PadesDigestResult(preparedPdfBytes, byteRange, signedAttrsDigestBase64, encodedSignedAttrs);
        } catch (Exception e) {
            throw new SigningException("PADES_DIGEST_FAILED", "PAdES digest computation failed", e);
        }
    }

    private byte[] buildSignedAttrs(byte[] documentDigest) throws Exception {
        ASN1EncodableVector attrs = new ASN1EncodableVector();
        attrs.add(new Attribute(CMSAttributes.contentType,
                new DERSet(PKCSObjectIdentifiers.data)));
        attrs.add(new Attribute(CMSAttributes.messageDigest,
                new DERSet(new DEROctetString(documentDigest))));
        // signingTime is intentionally excluded: it would make signedAttrs time-dependent,
        // which breaks the TX1.5 retry path (recomputing on retry gives different attrs,
        // invalidating the signature CSC produced during the first attempt).
        return new DERSet(attrs).getEncoded("DER");
    }
}
