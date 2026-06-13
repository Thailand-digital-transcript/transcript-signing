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
| XML signing | Apache Santuario 4.0.4 (manual DOM) |
| PDF signing | Apache PDFBox 3.0.6 + BouncyCastle 1.83 |
| Resilience | Resilience4j |
| Service discovery | Eureka (optional) |
| Saga plumbing | `saga-commons` 1.0.0-SNAPSHOT |

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
| `saga.compensation.transcript-signing` | `CompensateTranscriptSigningCommand` | `sagaId`, `sagaStep`, `correlationId`, `documentId` |

### Outbound (produced via outbox relay)

| Topic | Event | Trigger |
|-------|-------|---------|
| `saga.reply.transcript-signing` | `TranscriptSigningReplyEvent` (`SUCCESS` / `FAILURE` / `COMPENSATED`) | terminal state of each command |
| `transcript.signed` | `TranscriptSignedEvent` | successful sign |
| `document.archive` | `DocumentArchiveEvent` | successful sign |
| `transcript.signing.dlq` | original message | after 3 failed redeliveries |

### Storage layout

Signed artifacts are written to S3 under:

```
{FORMAT}/{documentId}/attempt-{retryCount}/original.{ext}
{FORMAT}/{documentId}/attempt-{retryCount}/signed.{ext}
```

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
- **Integration tests** (`*IT`, run under `-Pintegration`) — end-to-end through real Kafka/Postgres/MinIO containers with WireMock standing in for the CSC API. Cover the happy paths (XML + PDF), idempotent replay, and compensation.

Integration tests share containers across classes and Kafka topics are never purged, so test assertions match on a unique `sagaId`/`documentId` rather than reading the first available record.
