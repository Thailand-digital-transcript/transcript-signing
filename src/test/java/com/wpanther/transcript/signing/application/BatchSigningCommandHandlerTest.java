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
import com.wpanther.transcript.signing.infrastructure.config.properties.StorageProperties;
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
    BatchSagaReplyPort replyPort = mock(BatchSagaReplyPort.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    StorageProperties properties = new StorageProperties();

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
        properties.setBucketName("signed-transcripts");

        handler = new BatchSigningCommandHandler(repository, xadesPreparePort, padesEmbeddingPort,
                cscAuth, cscSign, resolver, storage, replyPort, tx, properties);
    }

    private BatchSigningCommand command(String corr, String... docIds) {
        var items = java.util.Arrays.stream(docIds)
                .map(d -> new BatchSigningCommand.Item(d, "num-" + d, "XML/" + d + "/orig.xml",
                        "transcripts", "XML/batch-1/" + d + "/signed.xml"))
                .toList();
        return new BatchSigningCommand(null, null, null, null, "saga-1", SagaStep.SIGN_XML, corr,
                "batch-1", SignerRole.REGISTRAR, SigningFormat.XML, items);
    }

    @Test
    void happyPath_signsAllItemsWithOneCscCall() {
        when(repository.findByCorrelationId("corr-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.download(any())).thenReturn("<x/>".getBytes());
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
                // PDF-phase source is now a (bucket, key) ref, like every other phase — the
                // orchestrator names the rendered PDF's bucket via TranscriptKeyResolver
                // instead of handing signing a presigned cross-bucket URL.
                .map(d -> new BatchSigningCommand.Item(d, "num-" + d,
                        d + "/transcript.pdf", "transcript-pdfs", "PDF/batch-1/" + d + "/signed.pdf"))
                .toList();
        return new BatchSigningCommand(null, null, null, null, "saga-1", SagaStep.SIGN_PDF, corr,
                "batch-1", SignerRole.SEAL, SigningFormat.PDF, items);
    }

    @Test
    void pdfFormat_signsWithPades_andUploadsSignedPdf() {
        when(repository.findByCorrelationId("corr-pdf")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // PDF arrives via documentStoragePort.download(StorageRef), same as XAdES sources —
        // downloadSource() no longer branches by format.
        when(storage.download(any())).thenReturn("%PDF-1.6 original".getBytes());
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

        // input fetched via the allow-listed storage port, from the orchestrator-named
        // bucket. Downloaded twice (prepare digest + embed recompute), mirroring the
        // XAdES two-pass structure. documentDownloadPort no longer exists (Task 8 deleted
        // it) — everything reads through documentStoragePort.download(StorageRef) now.
        verify(storage, times(2)).download(new com.wpanther.transcript.signing.domain.model.StorageRef(
                "transcript-pdfs", "d1/transcript.pdf"));
        verify(storage, never()).downloadByKey(anyString());
        // PAdES path used; XAdES path untouched
        verify(padesEmbeddingPort).embedSignature(any(), eq("PADES_SIG"), eq("CERT_SEAL"));
        verifyNoInteractions(xadesPreparePort);
        // the SIGNED PDF is uploaded under the target key the orchestrator named
        verify(storage).upload(any(), eq("PDF/batch-1/d1/signed.pdf"));
        verify(replyPort).publishBatchReply(eq("saga-1"), eq(SagaStep.SIGN_PDF), eq("corr-pdf"),
                eq("batch-1"), eq(true), argThat(items -> items.size() == 1));
    }

    @Test
    void completedJob_republishesReplyWithoutCsc() {
        var done = BatchSigningJob.rehydrate(java.util.UUID.randomUUID(), "corr-1", "batch-1", "saga-1",
                SignerRole.REGISTRAR, SigningFormat.XML, BatchJobStatus.COMPLETED,
                List.of(BatchSigningItem.rehydrate(java.util.UUID.randomUUID(), "d1", "n1",
                        "XML/d1/orig.xml", "transcripts", "XML/batch-1/d1/signed.xml",
                        BatchItemStatus.SIGNED, "Sig", null, "sig",
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
                "XML/d1/orig.xml", "transcripts", "XML/batch-1/d1/signed.xml",
                BatchItemStatus.SIGNED, "Sig-1",
                java.time.Instant.parse("2026-06-16T10:00:00Z"), "sig-d1",
                "XML/d1/signed.xml", "http://minio/signed.xml", null, 11L);
        var needsSign = BatchSigningItem.rehydrate(d2id, "d2", "n2",
                "XML/d2/orig.xml", "transcripts", "XML/batch-1/d2/signed.xml",
                BatchItemStatus.PENDING, null, null, null, null, null,
                null, null);
        var job = BatchSigningJob.rehydrate(java.util.UUID.randomUUID(), "corr-r", "batch-r",
                "saga-1", SignerRole.REGISTRAR, SigningFormat.XML, BatchJobStatus.SIGNING,
                List.of(alreadySigned, needsSign), 0L);
        when(repository.findByCorrelationId("corr-r")).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.download(any())).thenReturn("<x/>".getBytes());
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

    /**
     * A batch persisted before V5 has no source_bucket column populated; null means "read
     * from signing's own bucket" (StorageProperties.bucketName) — exactly where those
     * in-flight sources live, so a batch mid-flight across the deploy still resumes.
     * Domain-level coverage of the fallback already exists (BatchSigningItemTest), and
     * IT-level coverage exists (BatchSigningResumeIT), but nothing pinned it at the handler
     * level — this is the seam where downloadSource() actually picks the bucket.
     */
    @Test
    void preV5Item_withNullSourceBucket_readsFromSigningsOwnBucket() {
        var preV5Item = BatchSigningItem.rehydrate(java.util.UUID.randomUUID(), "d1", "num-d1",
                "XML/old/d1/signed.xml", /* sourceBucket */ null, "XML/batch-1/d1/signed.xml",
                BatchItemStatus.PENDING, null, null, null, null, null, null, null);
        var job = BatchSigningJob.rehydrate(java.util.UUID.randomUUID(), "corr-nb", "batch-1",
                "saga-1", SignerRole.REGISTRAR, SigningFormat.XML, BatchJobStatus.SIGNING,
                List.of(preV5Item), 0L);
        when(repository.findByCorrelationId("corr-nb")).thenReturn(Optional.of(job));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.download(any())).thenReturn("<x/>".getBytes());
        when(xadesPreparePort.prepare(any(), eq("CERT_REG"), any(), anyString()))
                .thenReturn(new XadesPreparation("DIGEST", new byte[]{1}));
        when(cscAuth.authorize(eq("cred-reg"), anyList(), eq("1111"))).thenReturn("SAD");
        when(cscSign.signHash(anyList(), eq("SAD"), eq("cred-reg"), anyString()))
                .thenReturn(new com.wpanther.transcript.signing.application.dto.CscSignatureResult(
                        "txn-nb", List.of("SIG_d1")));
        when(xadesPreparePort.embed(any(), eq("CERT_REG"), any(), anyString(), anyString()))
                .thenReturn("<signed/>".getBytes());
        when(storage.upload(any(), anyString()))
                .thenAnswer(inv -> new StorageResult(inv.getArgument(1), "url", 9L));

        handler.handleBatchSigning(command("corr-nb", "d1"));

        // downloadSource() is called once in the prepare phase and again in the embed
        // phase (same two-pass shape as every other item) — both must resolve the null
        // sourceBucket to signing's own bucket, never to null or the orchestrator's bucket.
        verify(storage, times(2)).download(new com.wpanther.transcript.signing.domain.model.StorageRef(
                "signed-transcripts", "XML/old/d1/signed.xml"));
    }

    @Test
    void embedFailure_marksItemFailedAndContinuesWithOthers() {
        when(repository.findByCorrelationId("corr-ef")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.download(any())).thenReturn("<x/>".getBytes());
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

    /**
     * The HSM bills per signature, so a failed authorize must never reach signHash. The
     * handler gets this from ordering alone — authorize is called first and its exception
     * propagates — but nothing pinned it until now.
     */
    @Test
    void authorizeFailure_neverCallsSignHash() {
        when(repository.findByCorrelationId("corr-auth")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.download(any())).thenReturn("<x/>".getBytes());
        when(xadesPreparePort.prepare(any(), eq("CERT_REG"), any(), anyString()))
                .thenReturn(new XadesPreparation("DIGEST", new byte[]{1}));
        when(cscAuth.authorize(eq("cred-reg"), anyList(), eq("1111")))
                .thenThrow(new SigningException("CSC_AUTH_EMPTY_SAD",
                        "CSC authorize returned empty SAD token"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> handler.handleBatchSigning(command("corr-auth", "d1")))
                .isInstanceOf(SigningException.class);

        verify(cscAuth, times(1)).authorize(eq("cred-reg"), anyList(), eq("1111"));
        verify(cscSign, never()).signHash(anyList(), anyString(), anyString(), anyString());
    }
}
