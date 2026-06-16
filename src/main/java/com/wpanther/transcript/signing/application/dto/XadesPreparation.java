package com.wpanther.transcript.signing.application.dto;

/**
 * Result of the FIRST pass of XAdES signing. {@code signedInfoDigestBase64} is the SHA-256 of
 * the canonicalized {@code ds:SignedInfo} — this is the value sent to CSC signHash. {@code
 * preparedDocumentXml} is the full document with the ds:Signature skeleton in place and an empty
 * SignatureValue; the SECOND pass injects the returned signature into it.
 */
public record XadesPreparation(String signedInfoDigestBase64, byte[] preparedDocumentXml) {}
