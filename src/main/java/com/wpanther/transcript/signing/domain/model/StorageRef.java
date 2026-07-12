package com.wpanther.transcript.signing.domain.model;

/** A bucket-qualified object reference. Never a URL. */
public record StorageRef(String bucket, String key) {}
