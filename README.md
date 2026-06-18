# Transcript Signing Service

Event-driven microservice that digitally signs academic transcripts as both **XAdES** (XML) and **PAdES** (PDF), using a remote **CSC (Cloud Signature Consortium)** signing API. Built for the Thai Digital Transcript ecosystem and reuses the same saga/outbox patterns as the reference `pdf-signing-service` and `xml-signing-service`.

The service has **no REST API** — it is driven entirely by Kafka commands and publishes its results back to Kafka, persisting state in PostgreSQL and signed artifacts in S3/MinIO.

---

## Features

- **Dual-format signing** — XAdES-BASELINE-B for XML, PAdES-B-B for PDF, dispatched by a `format` field.
- **Crash-safe saga** — a 7-phase workflow with explicit transaction boundaries (TX1 / TX1.5 / TX2) so every external call is recoverable.
- **Idempotency** — replaying a command for an already-COMPLETED document republishes the success reply without re-invoking CSC or re-uploading.
- **Transaction checkpointing** — the CSC `signHash` result is persisted (TX1.5) before embedding, so a crash mid-sign does not bill the HSM twice.
- **Outbox pattern** — all outbound events are written to the DB in the same transaction as the state change, then relayed to Kafka by a Camel timer.
- **Compensation** — idempotent rollback that deletes S3 artifacts and the DB record.
- **Resilience** — Resilience4j circuit breakers and retries on the CSC authorization/signature adapters.

---

## Tech Stack

| Concern | Technology |
|---------|-----------|
| Language / Framework | Java 21, Spring Boot 3.4.13, Spring Cloud 2024.0.0 |
| Messaging / Routing | Apache Camel 4.14.4, Apache Kafka |
| Persistence | PostgreSQL, Spring Data JPA, Flyway |
| Object storage | AWS SDK v2 (S3-compatible — MinIO in dev/test) |
| XML signing | Apache Santuario 4.0.4 (two-pass remote signing) |
| PDF signing | Apache PDFBox 3.0.6 + BouncyCastle 1.83 |
| Resilience | Resilience4j |
| Service discovery | Eureka (optional) |
| Saga plumbing | `transcript-saga-commons` 1.0.0-SNAPSHOT |

---

## Architecture

Hexagonal / ports-and-adapters. Package root: `com.wpanther.transcript.signing`.

```
application/   usecases, ports (interfaces), DTOs
  └─ usecase/SagaCommandHandler   ← orchestrates the 7-phase workflow
domain/        SignedTranscriptDocument, SigningFormat, repository ports
infrastructure/
  ├─ adapter/in/camel             Kafka consumers (signing/compensation) + outbox relay
  ├─ adapter/out/csc              CSC API: OAuth2, authorize, signHash
  ├─ adapter/out/xml | /pdf       XAdES / PAdES embedding
  ├─ adapter/out/storage          S3 upload/download/delete + presign
  ├─ adapter/out/messaging        Outbox adapters (MANDATORY tx)
  └─ persistence                  JPA entity, mapper, Flyway migrations
```

### Seven-phase signing workflow

```
0  format-validate  → 1  idempotency  → 2  pre-S3 upload  → 3  TX1: persist SIGNING
4a CSC signHash     → TX1.5: persist transactionId + pendingSignature
4b embed signature + upload signed doc → 5  TX2: mark COMPLETED + write 3 outbox events
                                                                  → 6  relay: publish to Kafka
```

- **TX1** creates/loads the document in `SIGNING` state.
- **TX1.5** stores the CSC result so a crash between signing and embedding is safe.
- **TX2** flips to `COMPLETED` and writes the success reply + `transcript.signed` + `document.archive` events atomically.

Compensation is a separate command-driven flow: missing record → immediate `COMPENSATED`; otherwise delete both S3 objects and the DB row.

---

## Kafka Interface

All topics are configurable under `app.kafka.topics` (defaults shown).

### Inbound (consumed)

| Topic | Command | Key fields |
|-------|---------|-----------|
| `saga.command.transcript-signing` | `ProcessTranscriptSigningCommand` | `sagaId`, `sagaStep`, `correlationId`, `documentId`, `documentNumber`, `format` (`XML`/`PDF`), `xmlContent`, `pdfUrl` |
| `saga.command.transcript-signing.batch` | `BatchSigningCommand` | `sagaId`, `sagaStep`, `correlationId`, `batchId`, `signerRole` (`REGISTRAR`/`DEAN`/`SEAL`), `format`, `items[]` (each `documentId`/`documentNumber`/`storageKey`) |
| `saga.compensation.transcript-signing` | `CompensateTranscriptSigningCommand` | `sagaId`, `sagaStep`, `correlationId`, `documentId` |

### Outbound (produced via outbox relay)

| Topic | Event | Trigger |
|-------|-------|---------|
| `saga.reply.transcript-signing` | `TranscriptSigningReplyEvent` (`SUCCESS` / `FAILURE` / `COMPENSATED`) | terminal state of each command |
| `transcript.signed` | `TranscriptSignedEvent` | successful sign |
| `document.archive` | `DocumentArchiveEvent` | successful sign |
| `transcript.signing.dlq` | original message | after 3 failed redeliveries |

### Storage layout

Single-doc signed artifacts are written to S3 under:

```
{FORMAT}/{documentId}/attempt-{retryCount}/original.{ext}
{FORMAT}/{documentId}/attempt-{retryCount}/signed.{ext}
```

Batch signed artifacts are written to S3 under:

```
{FORMAT}/{documentId}/orig.xml                          ← pre-uploaded by the orchestrator
{FORMAT}/{batchId}/{documentId}/signed.xml              ← produced by BatchSigningCommandHandler
```

Batch command consumers must pre-upload each item's original XML to its `storageKey` before publishing the `BatchSigningCommand`. The handler never re-uploads originals.

---

## Batch signing (1B)

For multi-document approval workflows (e.g. a registrar signing 50 transcripts in one batch), a dedicated batch command lets the service issue **one** CSC `authorize` + **one** multi-hash `signHash` per batch, with the per-credential CSC identity selected by `signerRole`.

### Selecting a CSC credential

`signerRole` (`REGISTRAR` / `DEAN` / `SEAL`) maps to a per-role CSC credential via `app.csc.credentials.{REGISTRAR,DEAN,SEAL}` (in `application.yml`). The application-layer handler resolves the credential through the `SignerCredentialResolver` port, so no infra-config import leaks into the domain. Each batch handler run uses exactly one role's credentials end-to-end.

### One multi-hash CSC call per batch

The handler builds `(item, sigId, signingTime, digest)` tuples for every item needing a fresh signature, then issues a single `cscAuthorizationPort.authorize(credentialId, hashes, pin)` + a single `cscSignaturePort.signHash(hashes, sad, credentialId, oid)`. The CSC returns one `signatures[]` array index-aligned to the inputs — no per-item HSM round-trips.

### Item-level idempotency

`BatchSigningJob` is keyed by `correlationId`. Each `BatchSigningItem` carries its own `sigId`/`signingTime`/`pendingSignature`/status. A re-delivered command:

- for a `COMPLETED` job → republishes the reply without touching CSC;
- for a `SIGNING` job → re-signs only items where `!isSigned() && !hasSignature()` (i.e. items not yet checkpointed), so a crash between `signHash` and the embed loop never re-bills the HSM;
- never overwrites an item's stored signed document in S3 once that item is in `SIGNED` state.

### Reply event

`BatchSigningReplyEvent` is published on `saga.reply.transcript-signing` (the same reply topic as single-doc, partitioned by `sagaId`). The top-level `ReplyStatus` is `SUCCESS` iff every item reached `SIGNED`, otherwise `FAILURE` — but the per-item `items[]` list is always populated so the orchestrator can mark/advance survivors and re-emit only the failed items.

### What is not yet implemented

- **PDF (PAdES) batch signing** — deferred to the Plan 2 PDF integration. The handler shape is identical with `format=PDF` and a `PadesEmbeddingPort` instead of `XadesPreparePort`.
- **Batch compensation** — deferred to the Plan 3 orchestrator work. The single-doc compensation flow is unchanged.

---

## Prerequisites

- **JDK 21**
- **Maven 3.9+**
- **Docker** (for local infra or running integration tests via Testcontainers)
- Running instances of: PostgreSQL, Kafka, an S3-compatible store (e.g. MinIO), and a CSC signing service.

---

## Build & Run

```bash
# Compile + unit tests (integration tests are skipped by default)
mvn verify

# Unit + integration tests (Testcontainers spins up Postgres/Kafka/MinIO; WireMock stubs CSC)
mvn clean verify -Pintegration

# Build the runnable jar
mvn clean package -DskipTests
java -jar target/transcript-signing-1.0.0-SNAPSHOT-exec.jar
```

The service starts on **port 8088**. Actuator endpoints (`health`, `info`, `metrics`, `prometheus`) are exposed under `/actuator`.

---

## Configuration

All settings are environment-variable overridable. Key ones:

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_HOST` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | `localhost` / `transcript_signing_db` / `postgres` / `postgres` | PostgreSQL connection |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |
| `S3_ENDPOINT` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` / `S3_BUCKET_NAME` | `http://localhost:9001` / `minioadmin` / `minioadmin` / `signed-transcripts` | S3/MinIO storage |
| `CSC_SERVICE_URL` / `CSC_TOKEN_URL` | `http://localhost:9000` / `…/oauth2/token` | CSC signing API |
| `CSC_CLIENT_ID` / `CSC_CLIENT_SECRET` / `CSC_CREDENTIAL_ID` / `CSC_PIN` | `transcript-signing-service` / _empty_ / `default-credential` / _empty_ | CSC auth |
| `XADES_SIGNATURE_LEVEL` / `PADES_SIGNATURE_LEVEL` | `XAdES-BASELINE-B` / `PAdES-B-B` | signature levels |
| `SIGNING_MAX_RETRIES` | `3` | max retry attempts per document |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | service discovery (optional) |
| `OUTBOX_RELAY_ENABLED` | `true` | enables the Camel outbox→Kafka relay |

Database schema is managed by Flyway migrations in `src/main/resources/db/migration`; JPA runs with `ddl-auto=validate`.

---

## Testing

- **Unit tests** (`*Test`) — fast, mock-based; cover the domain model, command handler branches, and adapter logic.
- **Integration tests** (`*IT`, run under `-Pintegration`) — end-to-end through real Kafka/Postgres/MinIO containers with WireMock standing in for the CSC API. Cover the happy paths (XML + PDF), idempotent replay, batch signing, batch resume, and compensation.

Integration tests share containers across classes and Kafka topics are never purged, so test assertions match on a unique `sagaId`/`documentId` rather than reading the first available record. The batch ITs (`BatchSigningPipelineIT`, `BatchSigningResumeIT`) use the pre-seed pattern (no `@SpyBean`, no context fork) to test item-level idempotency without the competing-consumer trap.
