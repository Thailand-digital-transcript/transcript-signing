package com.wpanther.transcript.signing.application;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.transcript.signing.application.dto.SignHashResult;
import com.wpanther.transcript.signing.application.dto.SigningResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.dto.event.CompensateTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.dto.event.ProcessTranscriptSigningCommand;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.application.usecase.*;
import com.wpanther.transcript.signing.domain.model.*;
import com.wpanther.transcript.signing.domain.repository.SignedTranscriptDocumentRepository;
import com.wpanther.transcript.signing.infrastructure.config.properties.SigningProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaCommandHandlerTest {

    @Mock SignedTranscriptDocumentRepository repository;
    @Mock XadesTranscriptSigningService xadesSigningService;
    @Mock PadesTranscriptSigningService padesSigningService;
    @Mock DocumentStoragePort documentStoragePort;
    @Mock DocumentDownloadPort documentDownloadPort;
    @Mock SagaReplyPort sagaReplyPort;
    @Mock TranscriptSignedEventPort transcriptSignedEventPort;
    @Mock DocumentArchivePort documentArchivePort;
    @Mock TransactionTemplate transactionTemplate;

    SigningProperties signingProperties;
    SagaCommandHandler handler;

    @BeforeEach
    void setUp() {
        signingProperties = new SigningProperties();
        signingProperties.setMaxRetries(3);

        handler = new SagaCommandHandler(
                repository, xadesSigningService, padesSigningService,
                documentStoragePort, documentDownloadPort,
                sagaReplyPort, transcriptSignedEventPort, documentArchivePort,
                transactionTemplate, signingProperties);

        // Make TransactionTemplate execute the callback immediately
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            var callback = inv.getArgument(0,
                    org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
    }

    // Phase 0: Format validation
    @Test
    void handleSigningCommand_nullFormat_publishesFailureImmediately() {
        var command = signingCommand("doc-001", null, "<xml/>", null);
        handler.handleSigningCommand(command);
        verify(sagaReplyPort).publishFailure(anyString(), any(), anyString(), contains("format"));
        verify(repository, never()).save(any());
    }

    // Phase 1: Idempotency
    @Test
    void handleSigningCommand_alreadyCompleted_republishesSuccess() {
        var doc = completedDoc("doc-001");
        when(repository.findByDocumentId("doc-001")).thenReturn(Optional.of(doc));
        var command = signingCommand("doc-001", SigningFormat.XML, "<xml/>", null);

        handler.handleSigningCommand(command);

        verify(sagaReplyPort).publishSuccess(anyString(), any(), anyString(),
                eq(doc.getSignedDocUrl()), eq(doc.getSignedDocSize()), eq(SigningFormat.XML));
        verify(xadesSigningService, never()).computeAndSign(any());
    }

    @Test
    void handleSigningCommand_transactionIdAlreadySet_skipsCscAndReembeds() {
        var doc = signingDocWithTransactionId("doc-001");
        when(repository.findByDocumentId("doc-001")).thenReturn(Optional.of(doc));
        byte[] xmlBytes = "<xml/>".getBytes();
        lenient().when(documentStoragePort.downloadByKey("XML/doc-001/attempt-0/original.xml")).thenReturn(xmlBytes);
        when(xadesSigningService.embedAndUpload(any(), eq("stored-sig"), eq("stored-cert"),
                eq("doc-001"), eq(0)))
                .thenReturn(new SigningResult("XML/doc-001/attempt-0/signed.xml",
                        "http://url/signed.xml", 500L, "XAdES-BASELINE-B", Instant.now()));
        var command = signingCommand("doc-001", SigningFormat.XML, "<xml/>", null);

        handler.handleSigningCommand(command);

        verify(xadesSigningService, never()).computeAndSign(any());
        verify(xadesSigningService).embedAndUpload(any(), eq("stored-sig"), eq("stored-cert"),
                eq("doc-001"), eq(0));
    }

    // Phase 3: Max retries
    @Test
    void handleSigningCommand_maxRetriesExceeded_publishesFailure() {
        var doc = signingDocWithRetryCount("doc-001", 3);
        when(repository.findByDocumentId("doc-001")).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenReturn(doc);
        var command = signingCommand("doc-001", SigningFormat.XML, "<xml/>", null);

        handler.handleSigningCommand(command);

        verify(sagaReplyPort).publishFailure(anyString(), any(), anyString(), contains("retry"));
    }

    // Happy path: XML
    @Test
    void handleSigningCommand_xmlHappyPath_completesAndPublishesThreeEvents() {
        when(repository.findByDocumentId("doc-001")).thenReturn(Optional.empty());
        byte[] xmlBytes = "<xml/>".getBytes();
        var originalStorage = new StorageResult("XML/doc-001/attempt-0/original.xml", "http://orig", xmlBytes.length);
        when(documentStoragePort.upload(xmlBytes, "XML/doc-001/attempt-0/original.xml"))
                .thenReturn(originalStorage);
        var newDoc = pendingDoc("doc-001", SigningFormat.XML, originalStorage);
        doAnswer(inv -> inv.getArgument(0)).when(repository).save(any());
        when(xadesSigningService.computeAndSign(xmlBytes))
                .thenReturn(new SignHashResult("txn-001", "sig==", "cert-pem"));
        when(xadesSigningService.embedAndUpload(xmlBytes, "sig==", "cert-pem", "doc-001", 0))
                .thenReturn(new SigningResult("XML/doc-001/attempt-0/signed.xml",
                        "http://signed", 999L, "XAdES-BASELINE-B", Instant.now()));
        var command = signingCommand("doc-001", SigningFormat.XML, "<xml/>", null);

        handler.handleSigningCommand(command);

        verify(sagaReplyPort).publishSuccess(anyString(), any(), anyString(),
                eq("http://signed"), eq(999L), eq(SigningFormat.XML));
        verify(transcriptSignedEventPort).publish(eq("doc-001"), anyString(), eq(SigningFormat.XML),
                eq("http://signed"), anyString(), any());
        verify(documentArchivePort).publish(eq("doc-001"), anyString(), eq(SigningFormat.XML),
                eq("XML/doc-001/attempt-0/signed.xml"), any());
    }

    // Phase 2: PDF download failure
    @Test
    void handleSigningCommand_pdfDownloadFails_publishesFailure() {
        when(repository.findByDocumentId("doc-002")).thenReturn(Optional.empty());
        when(documentDownloadPort.download(any())).thenThrow(new RuntimeException("connection refused"));
        var command = signingCommand("doc-002", SigningFormat.PDF, null, "http://host/doc.pdf");

        handler.handleSigningCommand(command);

        verify(sagaReplyPort).publishFailure(anyString(), any(), anyString(), contains("download"));
        verify(repository, never()).save(any());
    }

    // Compensation
    @Test
    void handleCompensationCommand_noRecord_publishesCompensatedImmediately() {
        when(repository.findByDocumentId("doc-001")).thenReturn(Optional.empty());
        var command = compensateCommand("doc-001");

        handler.handleCompensationCommand(command);

        verify(sagaReplyPort).publishCompensated(anyString(), any(), anyString());
        verify(documentStoragePort, never()).delete(any());
    }

    @Test
    void handleCompensationCommand_withRecordNoSignedDoc_deletesOnlyOriginalAndDb() {
        var doc = SignedTranscriptDocument.create("doc-003", "TH-2026-003", SigningFormat.PDF,
                "PDF/doc-003/attempt-0/original.pdf", "http://orig", 200L);
        doc.startSigning();
        when(repository.findByDocumentId("doc-003")).thenReturn(Optional.of(doc));
        var command = compensateCommand("doc-003");

        handler.handleCompensationCommand(command);

        verify(documentStoragePort).delete("PDF/doc-003/attempt-0/original.pdf");
        verify(documentStoragePort, never()).delete(null);
        verify(repository).deleteById(doc.getId());
        verify(sagaReplyPort).publishCompensated(anyString(), any(), anyString());
    }

    @Test
    void handleCompensationCommand_withRecord_deletesS3AndDb() {
        var doc = completedDoc("doc-001");
        when(repository.findByDocumentId("doc-001")).thenReturn(Optional.of(doc));
        var command = compensateCommand("doc-001");

        handler.handleCompensationCommand(command);

        verify(documentStoragePort).delete(doc.getOriginalDocPath());
        verify(documentStoragePort).delete(doc.getSignedDocPath());
        verify(repository).deleteById(doc.getId());
        verify(sagaReplyPort).publishCompensated(anyString(), any(), anyString());
    }

    // Helpers
    private ProcessTranscriptSigningCommand signingCommand(String docId, SigningFormat format,
                                                            String xml, String pdfUrl) {
        return new ProcessTranscriptSigningCommand(null, null, null, null,
                "saga-001", SagaStep.SIGN_XML, "corr-001",
                docId, "TH-2026-001", format, xml, pdfUrl);
    }

    private CompensateTranscriptSigningCommand compensateCommand(String docId) {
        return new CompensateTranscriptSigningCommand(null, null, null, null,
                "saga-001", SagaStep.SIGN_XML, "corr-001", docId);
    }

    private SignedTranscriptDocument pendingDoc(String docId, SigningFormat format,
                                                 StorageResult orig) {
        return SignedTranscriptDocument.create(docId, "TH-2026-001", format,
                orig.path(), orig.url(), orig.size());
    }

    private SignedTranscriptDocument signingDocWithTransactionId(String docId) {
        var doc = SignedTranscriptDocument.create(docId, "TH-2026-001", SigningFormat.XML,
                "XML/doc-001/attempt-0/original.xml", "http://orig", 100L);
        doc.startSigning();
        doc.saveTransactionCheckpoint("txn-001", "stored-sig", "stored-cert");
        return doc;
    }

    private SignedTranscriptDocument signingDocWithRetryCount(String docId, int retries) {
        var doc = SignedTranscriptDocument.create(docId, "TH-2026-001", SigningFormat.XML,
                "XML/doc-001/attempt-0/original.xml", "http://orig", 100L);
        for (int i = 0; i < retries; i++) doc.markFailed("err");
        doc.startSigning();
        return doc;
    }

    private SignedTranscriptDocument completedDoc(String docId) {
        var doc = SignedTranscriptDocument.create(docId, "TH-2026-001", SigningFormat.XML,
                "XML/doc-001/attempt-0/original.xml", "http://orig", 100L);
        doc.startSigning();
        doc.markCompleted("XML/doc-001/attempt-0/signed.xml", "http://signed", 999L,
                "XAdES-BASELINE-B", Instant.now());
        return doc;
    }
}
