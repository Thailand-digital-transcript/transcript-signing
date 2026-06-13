package com.wpanther.transcript.signing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class TranscriptSigningApplication {
    public static void main(String[] args) {
        SpringApplication.run(TranscriptSigningApplication.class, args);
    }
}
