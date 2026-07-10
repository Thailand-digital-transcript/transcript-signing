package com.wpanther.transcript.signing.application;

import com.wpanther.transcript.signing.application.dto.PadesDigestResult;
import com.wpanther.transcript.signing.application.dto.StorageResult;
import com.wpanther.transcript.signing.application.dto.XadesPreparation;
import com.wpanther.transcript.signing.application.dto.event.BatchSigningCommand;
import com.wpanther.transcript.signing.application.port.out.*;
import com.wpanther.transcript.signing.application.usecase.BatchSigningCommandHandler;
import com.wpanther.transcript.signing.domain.model.*;
import com.wpanther.transcript.signing.application.port.out.SignerCredentialResolver.ResolvedSigner;
import com.wpanther.transcript.signing.domain.repository.BatchSigningJobRepository;
import com.wpanther.transcript.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchSigningCommandHandlerTest {

    BatchSigningJobRepository repository = mock(BatchSigningJobRepository.class);
    XadesPreparePort xadesPreparePort = mock(XadesPreparePort.class);
    PadesEmbeddingPort padesEmbeddingPort = mock(PadesEmbeddingPort.class);
    CscAuthorizationPort cscAuth = mock(CscAuthorizationPort.class);
    CscSignaturePort cscSign = mock(CscSignaturePort.class);
    SignerCredentialResolver resolver = mock(SignerCredentialResolver.class);
    DocumentStoragePort storage = mock(DocumentStoragePort.class);
    DocumentDownloadPort downloadPort = mock(DocumentDownloadPort.class);
    BatchSagaReplyPort replyPort = mock(BatchSagaReplyPort.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);

    BatchSigningCommandHandler handler;

    @BeforeEach
    void setUp() {
        // make TransactionTemplate.execute run the callback inline
        when(tx.execute(any())).thenAnswer(inv ->
                ((org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0))
                        .doInTransaction(null));
        when(resolver.resolve(SignerRole.REGISTRAR)).thenReturn(
                new ResolvedSigner("cred-reg", "1111", "CERT_REG", "2.16.840.1.101.3.4.2.1"));
        when(resolver.resolve(SignerRole.SEAL)).thenReturn(
                new ResolvedSigner("cred-seal", "2222", "CERT_SEAL", "2.16.840.1.101.3.4.2.1"));

        handler = new BatchSigningCommandHandler(repository, xadesPreparePort, padesEmbeddingPort,
                cscAuth, cscSign, resolver, storage, downloadPort, replyPort, tx);
    }

    private BatchSigningCommand command(String corr, String... docIds) {
        var items = java.util.Arrays.stream(docIds)
                .map(d -> new BatchSigningCommand.Item(d, "num-" + d, "XML/" + d + "/orig.xml"))
                .toList();
        return new BatchSigningCommand(null, null, null, null, "saga-1", SagaStep.SIGN_XML, corr,
                "batch-1", SignerRole.REGISTRAR, SigningFormat.XML, items);
    }

    @Test
    void happyPath_signsAllItemsWithOneCscCall() {
        when(repository.findByCorrelationId("corr-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.downloadByKey(anyString())).thenReturn("<x/>".getBytes());
        when(xadesPreparePort.prepare(any(), eq("CERT_REG"), any(), anyString()))
                .thenReturn(new XadesPreparation("DIGEST", new byte[]{1}));
        when(cscAuth.authorize(eq("cred-reg"), anyList(), eq("1111"))).thenReturn("SAD");
        when(cscSign.signHash(anyList(), eq("SAD"), eq("cred-reg"), anyString()))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-batch-123", List.of("SIG_d1", "SIG_d2")));
        when(xadesPreparePort.embed(any(), eq("CERT_REG"), any(), anyString(), anyString()))
                .thenReturn("<signed/>".getBytes());
        when(storage.upload(any(), anyString()))
                .thenAnswer(inv -> new StorageResult(inv.getArgument(1), "url", 9L));

        handler.handleBatchSigning(command("corr-1", "d1", "d2"));

        // exactly one authorize + one signHash, each with a 2-element hash list
        verify(cscAuth, times(1)).authorize(eq("cred-reg"),
                argThat((List<String> l) -> l.size() == 2), eq("1111"));
        verify(cscSign, times(1)).signHash(argThat((List<String> l) -> l.size() == 2),
                eq("SAD"), eq("cred-reg"), anyString());
        verify(replyPort).publishBatchReply(eq("saga-1"), eq(SagaStep.SIGN_XML), eq("corr-1"),
                eq("batch-1"), eq(true), argThat(items -> items.size() == 2));
    }

    private BatchSigningCommand pdfCommand(String corr, String... docIds) {
        var items = java.util.Arrays.stream(docIds)
                // PDF-phase source key is the presigned cross-bucket URL of the rendered PDF
                .map(d -> new BatchSigningCommand.Item(d, "num-" + d,
                        "http://minio:9000/transcript-pdfs/" + d + "/transcript.pdf?sig=x"))
                .toList();
        return new BatchSigningCommand(null, null, null, null, "saga-1", SagaStep.SIGN_PDF, corr,
                "batch-1", SignerRole.SEAL, SigningFormat.PDF, items);
    }

    @Test
    void pdfFormat_signsWithPades_andUploadsSignedPdf() {
        when(repository.findByCorrelationId("corr-pdf")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // PDF arrives via the presigned URL, not downloadByKey
        when(downloadPort.download(anyString())).thenReturn("%PDF-1.6 original".getBytes());
        var digest = new PadesDigestResult("%PDF prepared".getBytes(), "PDFDIGEST", new byte[]{9});
        when(padesEmbeddingPort.computeByteRangeDigest(any())).thenReturn(digest);
        when(cscAuth.authorize(eq("cred-seal"), anyList(), eq("2222"))).thenReturn("SAD");
        when(cscSign.signHash(anyList(), eq("SAD"), eq("cred-seal"), anyString()))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-pades-1", List.of("PADES_SIG")));
        when(padesEmbeddingPort.embedSignature(any(), eq("PADES_SIG"), eq("CERT_SEAL")))
                .thenReturn("%PDF-1.6 signed".getBytes());
        when(storage.upload(any(), anyString()))
                .thenAnswer(inv -> new StorageResult(inv.getArgument(1), "url", 12L));

        handler.handleBatchSigning(pdfCommand("corr-pdf", "d1"));

        // input fetched via the presigned URL, never via downloadByKey. Downloaded twice
        // (prepare digest + embed recompute), mirroring the XAdES two-pass structure.
        verify(downloadPort, times(2)).download("http://minio:9000/transcript-pdfs/d1/transcript.pdf?sig=x");
        verify(storage, never()).downloadByKey(anyString());
        // PAdES path used; XAdES path untouched
        verify(padesEmbeddingPort).embedSignature(any(), eq("PADES_SIG"), eq("CERT_SEAL"));
        verifyNoInteractions(xadesPreparePort);
        // the SIGNED PDF is uploaded under a .pdf key (not signed.xml)
        verify(storage).upload(any(), eq("PDF/batch-1/d1/signed.pdf"));
        verify(replyPort).publishBatchReply(eq("saga-1"), eq(SagaStep.SIGN_PDF), eq("corr-pdf"),
                eq("batch-1"), eq(true), argThat(items -> items.size() == 1));
    }

    @Test
    void completedJob_republishesReplyWithoutCsc() {
        var done = BatchSigningJob.rehydrate(java.util.UUID.randomUUID(), "corr-1", "batch-1", "saga-1",
                SignerRole.REGISTRAR, SigningFormat.XML, BatchJobStatus.COMPLETED,
                List.of(BatchSigningItem.rehydrate(java.util.UUID.randomUUID(), "d1", "n1",
                        "XML/d1/orig.xml", BatchItemStatus.SIGNED, "Sig", null, "sig",
                        "XML/d1/signed.xml", "url", null, 10L)), 1L);
        when(repository.findByCorrelationId("corr-1")).thenReturn(Optional.of(done));

        handler.handleBatchSigning(command("corr-1", "d1"));

        verifyNoInteractions(cscAuth, cscSign);
        verify(replyPort).publishBatchReply(eq("saga-1"), any(), eq("corr-1"), eq("batch-1"),
                eq(true), anyList());
    }

    @Test
    void redelivery_skipsAlreadySignedItemsAndNeverRebillsHsm() {
        // d1 is already SIGNED (after a prior attempt), d2 is PENDING with no signature yet.
        // The job is rehydrated with these states, and itemsNeedingFreshSignature() must
        // return only d2 — a single-element hash list goes to CSC, and d1 is never billed
        // again. d1 also has no embed work to do, so it never reaches the CSC either.
        java.util.UUID d1id = java.util.UUID.randomUUID();
        java.util.UUID d2id = java.util.UUID.randomUUID();
        var alreadySigned = BatchSigningItem.rehydrate(d1id, "d1", "n1",
                "XML/d1/orig.xml", BatchItemStatus.SIGNED, "Sig-1",
                java.time.Instant.parse("2026-06-16T10:00:00Z"), "sig-d1",
                "XML/d1/signed.xml", "http://minio/signed.xml", null, 11L);
        var needsSign = BatchSigningItem.rehydrate(d2id, "d2", "n2",
                "XML/d2/orig.xml", BatchItemStatus.PENDING, null, null, null, null, null,
                null, null);
        var job = BatchSigningJob.rehydrate(java.util.UUID.randomUUID(), "corr-r", "batch-r",
                "saga-1", SignerRole.REGISTRAR, SigningFormat.XML, BatchJobStatus.SIGNING,
                List.of(alreadySigned, needsSign), 0L);
        when(repository.findByCorrelationId("corr-r")).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.downloadByKey(anyString())).thenReturn("<x/>".getBytes());
        when(xadesPreparePort.prepare(any(), eq("CERT_REG"), any(), anyString()))
                .thenReturn(new XadesPreparation("DIGEST_d2", new byte[]{1}));
        when(cscAuth.authorize(eq("cred-reg"), anyList(), eq("1111"))).thenReturn("SAD");
        when(cscSign.signHash(anyList(), eq("SAD"), eq("cred-reg"), anyString()))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-redelivery", List.of("SIG_d2")));
        when(xadesPreparePort.embed(any(), eq("CERT_REG"), any(), anyString(), anyString()))
                .thenReturn("<signed-d2/>".getBytes());
        when(storage.upload(any(), anyString()))
                .thenAnswer(inv -> new StorageResult(inv.getArgument(1), "url", 9L));

        handler.handleBatchSigning(command("corr-r", "d1", "d2"));

        // only d2 is billed; the hash list size is 1
        verify(cscAuth, times(1)).authorize(eq("cred-reg"),
                argThat((List<String> l) -> l.size() == 1), eq("1111"));
        verify(cscSign, times(1)).signHash(argThat((List<String> l) -> l.size() == 1),
                eq("SAD"), eq("cred-reg"), anyString());
        // d1 was already signed → no upload for d1; only d2's embed+upload runs
        verify(storage, times(1)).upload(any(), eq("XML/batch-1/d2/signed.xml"));
        // all items signed in the end (d1 was signed in the rehydrated state, d2 was just signed)
        verify(replyPort).publishBatchReply(eq("saga-1"), eq(SagaStep.SIGN_XML), eq("corr-r"),
                eq("batch-1"), eq(true), argThat(items -> items.size() == 2));
    }

    @Test
    void embedFailure_marksItemFailedAndContinuesWithOthers() {
        when(repository.findByCorrelationId("corr-ef")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.downloadByKey(anyString())).thenReturn("<x/>".getBytes());
        when(xadesPreparePort.prepare(any(), eq("CERT_REG"), any(), anyString()))
                .thenReturn(new XadesPreparation("DIGEST", new byte[]{1}));
        when(cscAuth.authorize(eq("cred-reg"), anyList(), eq("1111"))).thenReturn("SAD");
        when(cscSign.signHash(anyList(), eq("SAD"), eq("cred-reg"), anyString()))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-ef", List.of("SIG_d1", "SIG_d2")));
        // First embed call (d1) throws — covers the catch + markFailed path. Second
        // call (d2) succeeds, exercising the loop's continue-after-throw behaviour.
        when(xadesPreparePort.embed(any(), eq("CERT_REG"), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("S3 unreachable"))
                .thenReturn("<signed-d2/>".getBytes());
        when(storage.upload(any(), anyString()))
                .thenAnswer(inv -> new StorageResult(inv.getArgument(1), "url", 9L));

        handler.handleBatchSigning(command("corr-ef", "d1", "d2"));

        // d1's embed threw, so no upload for d1; d2 succeeded and was uploaded
        verify(storage, never()).upload(any(), eq("XML/batch-1/d1/signed.xml"));
        verify(storage, times(1)).upload(any(), eq("XML/batch-1/d2/signed.xml"));
        // the per-item reply reflects the mixed outcome: d1 FAILED, d2 SIGNED, allSucceeded=false
        verify(replyPort).publishBatchReply(eq("saga-1"), eq(SagaStep.SIGN_XML), eq("corr-ef"),
                eq("batch-1"), eq(false),
                argThat(items -> items.size() == 2
                        && items.stream().anyMatch(i -> "FAILED".equals(i.getStatus())
                                && "d1".equals(i.getDocumentId())
                                && "S3 unreachable".equals(i.getErrorMessage()))
                        && items.stream().anyMatch(i -> "SIGNED".equals(i.getStatus())
                                && "d2".equals(i.getDocumentId()))));
    }
}
