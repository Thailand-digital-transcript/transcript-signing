package com.wpanther.transcript.signing.infrastructure.adapter.out.download;

import com.wpanther.transcript.signing.application.port.out.DocumentDownloadPort;
import com.wpanther.transcript.signing.domain.model.SigningException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@Slf4j
@Component
public class HttpDocumentDownloadAdapter implements DocumentDownloadPort {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 30_000;
    private static final long MAX_SIZE_BYTES    = 50 * 1024 * 1024; // 50 MB

    @Override
    public byte[] download(String url) {
        log.debug("Downloading document from {}", redact(url));
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new SigningException("DOWNLOAD_HTTP_ERROR",
                        "HTTP " + status + " downloading document from " + redact(url));
            }
            byte[] content = readBounded(conn.getInputStream());
            log.debug("Downloaded {} bytes from {}", content.length, redact(url));
            return content;
        } catch (SigningException e) {
            throw e;
        } catch (IOException e) {
            throw new SigningException("DOWNLOAD_IO_ERROR",
                    "IO error downloading document: " + e.getMessage(), e);
        }
    }

    private byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > MAX_SIZE_BYTES) {
                throw new SigningException("DOWNLOAD_TOO_LARGE",
                        "Document exceeds maximum size of " + MAX_SIZE_BYTES + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private String redact(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?[redacted]" : url;
    }
}
