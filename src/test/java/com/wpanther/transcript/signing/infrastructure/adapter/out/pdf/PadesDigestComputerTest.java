package com.wpanther.transcript.signing.infrastructure.adapter.out.pdf;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class PadesDigestComputerTest {

    PadesDigestComputer computer = new PadesDigestComputer();

    @Test
    void computeByteRangeDigest_returnsNonNullDigest() throws Exception {
        byte[] pdfBytes = createMinimalPdf();
        PadesDigestResult result = computer.computeByteRangeDigest(pdfBytes);
        assertThat(result).isNotNull();
        assertThat(result.signedAttrsDigestBase64()).isNotBlank();
        assertThat(result.encodedSignedAttrs()).isNotEmpty();
        assertThat(result.preparedPdfBytes()).isNotEmpty();
        assertThat(result.byteRange()).hasSize(4);
        // signedAttrsDigestBase64 is a valid base64-encoded SHA-256
        byte[] decoded = Base64.getDecoder().decode(result.signedAttrsDigestBase64());
        assertThat(decoded).hasSize(32);
    }

    @Test
    void computeByteRangeDigest_deterministicForSameInput() throws Exception {
        byte[] pdfBytes = createMinimalPdf();
        PadesDigestResult r1 = computer.computeByteRangeDigest(pdfBytes);
        PadesDigestResult r2 = computer.computeByteRangeDigest(pdfBytes);
        // signedAttrs contain only contentType + messageDigest (no signingTime),
        // so the same PDF always produces the same hash — critical for TX1.5 retry correctness.
        assertThat(r1.signedAttrsDigestBase64()).isEqualTo(r2.signedAttrsDigestBase64());
        assertThat(r1.encodedSignedAttrs()).isEqualTo(r2.encodedSignedAttrs());
    }

    private byte[] createMinimalPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}
