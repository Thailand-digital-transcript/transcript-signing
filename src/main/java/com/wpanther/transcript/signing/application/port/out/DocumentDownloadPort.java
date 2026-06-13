package com.wpanther.transcript.signing.application.port.out;

public interface DocumentDownloadPort {
    byte[] download(String url);
}
