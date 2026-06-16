package com.wpanther.transcript.signing.application.dto.event;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaReply;

import java.util.List;

public class BatchSigningReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    private String batchId;
    private List<ItemResult> items;

    public static BatchSigningReplyEvent of(String sagaId, SagaStep sagaStep, String correlationId,
                                            String batchId, boolean allSucceeded,
                                            List<ItemResult> items) {
        BatchSigningReplyEvent reply = new BatchSigningReplyEvent(sagaId, sagaStep, correlationId,
                allSucceeded ? ReplyStatus.SUCCESS : ReplyStatus.FAILURE);
        reply.batchId = batchId;
        reply.items = items;
        return reply;
    }

    private BatchSigningReplyEvent(String sagaId, SagaStep sagaStep, String correlationId,
                                   ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    public String getBatchId()        { return batchId; }
    public List<ItemResult> getItems() { return items; }

    public static class ItemResult {
        private String documentId;
        private String status;        // SIGNED | FAILED
        private String signedDocUrl;
        private Long signedDocSize;
        private String errorMessage;

        public static ItemResult signed(String documentId, String url, long size) {
            ItemResult r = new ItemResult();
            r.documentId = documentId; r.status = "SIGNED"; r.signedDocUrl = url; r.signedDocSize = size;
            return r;
        }
        public static ItemResult failed(String documentId, String error) {
            ItemResult r = new ItemResult();
            r.documentId = documentId; r.status = "FAILED"; r.errorMessage = error;
            return r;
        }

        public String getDocumentId()   { return documentId; }
        public String getStatus()       { return status; }
        public String getSignedDocUrl() { return signedDocUrl; }
        public Long getSignedDocSize()  { return signedDocSize; }
        public String getErrorMessage() { return errorMessage; }
    }
}
