# Mini WSA — Web Security Analytics Pipeline

A Spring Boot monolith that ingests, enriches, stores, and aggregates security events (DLRs) from a WAF/CDN edge network.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Storage Choice — Why PostgreSQL](#storage-choice--why-postgresql)
3. [Run Instructions](#run-instructions)
4. [API Reference & curl Examples](#api-reference--curl-examples)
5. [Data Generator](#data-generator)
6. [Running the Tests](#running-the-tests)
7. [Project Structure](#project-structure)

---

## Architecture

```mermaid
flowchart LR
    Client([REST / Kafka])
    RateLimit[Rate Limit\nRedis]
    Ingestion[Ingestion\nvalidate + persist]
    Enrichment[Enrichment\nclassify + score]
    Analytics[Stats & Samples\nread APIs]
    DB[(PostgreSQL)]

    Client --> RateLimit --> Ingestion --> Enrichment --> DB
    RateLimit --> Analytics --> DB
```

### Enrichment Pipeline Detail

**Threat score formula (0–100):**
```
score = severityScore(rule.severity)     // CRITICAL=40, HIGH=30, MEDIUM=20, LOW=10
      + actionScore(action)              // DENY=20, ALERT=10, MONITOR=0
      + pathBonus(path)                  // path contains /admin or /login → +15
      + repeatOffenderBonus(clientIp)    // COUNT > 5 in last 10 min → +15
score = min(score, 100)
```

**Attack-type classification:**
```
INJECTION          → "SQL/Command Injection"
XSS                → "Cross-Site Scripting"
PROTOCOL_VIOLATION → "Protocol Anomaly"
DATA_LEAKAGE       → "Data Exfiltration"
BOT                → "Bot Activity"
DOS                → "Denial of Service"
RATE_LIMIT         → "Rate Limiting"
```

---

## Storage Choice — Why PostgreSQL

**PostgreSQL** is the sole persistent store for enriched security events.

| Requirement | How PostgreSQL satisfies it |
|---|---|
| Time-range analytics | Native `TIMESTAMPTZ` with index-supported `BETWEEN` scans |
| Aggregations | `GROUP BY`, `COUNT`, `AVG` execute at the DB layer with no entity hydration |
| Idempotent ingestion | `event_id UNIQUE` constraint rejects duplicates atomically (ACID) |
| Repeat-offender detection | Plain `COUNT(*)` on `(client_ip, received_at)` — correct after restarts, zero extra infrastructure |
| Operational simplicity | Single DB, standard SQL, Flyway migration |

**`event_timestamp` vs `received_at`:** Two timestamp columns are maintained deliberately.

- `event_timestamp` — the client-reported time of the attack; used by analytics queries (`from`/`to` filters) so the SOC sees *when the attack occurred*.
- `received_at` — the server-assigned ingestion time; used by `RepeatOffenderDetectionService` so clients cannot bypass detection by backdating events.

**Database indexes:**

| Index | Columns | Used by |
|---|---|---|
| `idx_se_config_timestamp` | `(config_id, event_timestamp)` | Stats summary + samples time-range filter |
| `idx_se_ip_received` | `(client_ip, received_at)` | Repeat-offender `COUNT` query |
| `idx_se_category_timestamp` | `(rule_category, event_timestamp)` | Samples category filter + `byCategory` aggregation |
| `idx_se_action_timestamp` | `(action, event_timestamp)` | Samples action filter + `byAction` aggregation |

**Tradeoff vs alternatives:**
- *ClickHouse* — significantly faster at 100M+ row aggregations, but adds dialect complexity and operational overhead disproportionate for this scope.
- *Redis* — used for rate-limiting state (write-heavy, ephemeral, shared-across-instances) because TTL-based counters are exactly what Redis excels at; *not* suitable as the event store.
- *Kafka* *(bonus)* — used for async event delivery, not storage; the same `IngestionService` pipeline persists events to PostgreSQL regardless of whether they arrived via REST or Kafka.

---

## Run Instructions

### Prerequisites

- Docker & Docker Compose
- Java 21+
- Maven (or use the included `./mvnw` wrapper)

### Option A — Full Stack via Docker Compose (recommended)

```bash
# Build the application JAR first
./mvnw clean package -DskipTests

# Start PostgreSQL + Redis + Kafka + the app
docker compose up --build

# The app is now available at http://localhost:8080
```

To stop and remove volumes:
```bash
docker compose down -v
```

### Option B — Local Development (app only)

Start PostgreSQL (and optionally Redis + Kafka) separately, then run:

```bash
# Start infrastructure only
docker compose up postgres redis kafka -d

# Run the Spring Boot app
./mvnw spring-boot:run
```

The app connects to `localhost:5432` (PostgreSQL), `localhost:6379` (Redis), and `localhost:9092` (Kafka) by default. All credentials are in `src/main/resources/application.yml`.

---

## API Reference & curl Examples

### POST /v1/events/ingest — Ingest Events

Accepts a **single event** or a **JSON array** of events. Both are handled by the same endpoint.

**Single event:**
```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-00001",
    "timestamp": "2026-07-16T10:00:00Z",
    "configId": 14227,
    "clientIp": "203.0.113.42",
    "hostname": "www.example.com",
    "path": "/admin/login",
    "method": "POST",
    "statusCode": 403,
    "userAgent": "sqlmap/1.7",
    "rule": {
      "id": "950001",
      "name": "SQL_INJECTION",
      "message": "SQL Injection Detected",
      "severity": "CRITICAL",
      "category": "INJECTION"
    },
    "action": "DENY",
    "geoLocation": { "country": "CN", "city": "Beijing" },
    "requestSize": 1024,
    "responseSize": 256
  }' | jq .
```

**Expected 201 response:**
```json
{ "ingested": 1, "failed": 0, "errors": [] }
```

**Batch of events:**
```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '[
    {
      "eventId": "evt-00002",
      "timestamp": "2026-07-16T10:01:00Z",
      "configId": 14227,
      "clientIp": "198.51.100.17",
      "path": "/search",
      "rule": { "severity": "HIGH", "category": "XSS" },
      "action": "ALERT"
    },
    {
      "eventId": "evt-00003",
      "timestamp": "2026-07-16T10:02:00Z",
      "configId": 14227,
      "clientIp": "172.16.0.1",
      "rule": { "severity": "LOW", "category": "BOT" },
      "action": "MONITOR"
    }
  ]' | jq .
```

**Validation error (400) — missing required field:**
```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-bad",
    "timestamp": "2026-07-16T10:00:00Z",
    "configId": 14227,
    "rule": { "severity": "HIGH", "category": "BOT" },
    "action": "DENY"
  }' | jq .
# Missing clientIp → 400 Bad Request
```

**Duplicate event (409 in single / error entry in batch):**
```bash
# Send the same evt-00001 again
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{ "eventId": "evt-00001", ... }' | jq .
# → 400 with message containing "duplicate"
```

---

### GET /v1/stats/summary — Aggregated Statistics

`from` and `to` are required ISO-8601 timestamps. `configId` is optional.

```bash
# Stats for a specific config in the last 24 hours
curl -s "http://localhost:8080/v1/stats/summary?\
configId=14227\
&from=2026-07-15T00:00:00Z\
&to=2026-07-16T23:59:59Z" | jq .
```

```bash
# Stats across all configurations
curl -s "http://localhost:8080/v1/stats/summary?\
from=2026-07-01T00:00:00Z\
&to=2026-07-16T23:59:59Z" | jq .
```

**Example 200 response:**
```json
{
  "totalEvents": 3842,
  "avgThreatScore": 67.4,
  "byCategory": {
    "INJECTION": { "count": 1204, "avgScore": 85.2 },
    "BOT":       { "count":  987, "avgScore": 45.1 }
  },
  "byAction": {
    "DENY":    2100,
    "ALERT":   1400,
    "MONITOR":  342
  },
  "topAttackers": [
    { "clientIp": "203.0.113.42", "eventCount": 312 },
    { "clientIp": "198.51.100.17", "eventCount": 289 }
  ],
  "topTargetedPaths": [
    { "path": "/admin/login", "eventCount": 541 },
    { "path": "/api/v1/users", "eventCount": 320 }
  ]
}
```

---

### GET /v1/events/samples — Paginated Event Samples

All parameters are optional. Results are sorted by `event_timestamp DESC`.

```bash
# First page — all events, default limit 20
curl -s "http://localhost:8080/v1/events/samples" | jq .

# Filter by configId, category, and action with pagination
curl -s "http://localhost:8080/v1/events/samples?\
configId=14227\
&from=2026-07-15T00:00:00Z\
&to=2026-07-16T23:59:59Z\
&category=INJECTION\
&action=DENY\
&limit=10\
&offset=0" | jq .

# Next page
curl -s "http://localhost:8080/v1/events/samples?\
category=INJECTION&limit=10&offset=10" | jq .
```

**Query parameter reference:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `configId` | Long | — | Filter to a specific configuration |
| `from` | ISO-8601 | — | Filter events at or after this time |
| `to` | ISO-8601 | — | Filter events at or before this time |
| `category` | String | — | One of: `INJECTION`, `XSS`, `PROTOCOL_VIOLATION`, `DATA_LEAKAGE`, `BOT`, `DOS`, `RATE_LIMIT` |
| `action` | String | — | One of: `DENY`, `ALERT`, `MONITOR` |
| `limit` | int | `20` | Results per page (1–100) |
| `offset` | int | `0` | Pagination offset |

**Example 200 response:**
```json
{
  "total": 1204,
  "data": [
    {
      "id": 9871,
      "eventId": "evt-00001",
      "eventTimestamp": "2026-07-16T10:00:00Z",
      "receivedAt": "2026-07-16T10:00:00.123Z",
      "configId": 14227,
      "clientIp": "203.0.113.42",
      "path": "/admin/login",
      "action": "DENY",
      "attackType": "SQL/Command Injection",
      "threatScore": 90
    }
  ]
}
```

---

## Data Generator

The `datagen` Spring profile activates a self-contained `ApplicationRunner` that generates realistic traffic and POSTs it to a running Mini WSA instance. The main app must be running before the generator is started.

### Two-Phase Strategy

1. **Seed phase** — sends ≥7 events per designated attacker IP so that subsequent events from those IPs cross the repeat-offender threshold (`count > 5` in last 10 minutes) and receive the +15 threat-score bonus.
2. **Main phase** — generates the configured `count` of events with a 30/70 split between attacker IPs and normal background IPs. All events are shuffled before batching to produce a realistic interleaved time-series.

### Running the Generator

```bash
# Terminal 1 — start the main app (must be running first)
./mvnw spring-boot:run

# Terminal 2 — run the data generator (exits when done)
./mvnw spring-boot:run -Dspring-boot.run.profiles=datagen
```

**Override defaults via system properties:**
```bash
# Generate 50 000 events in batches of 200
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=datagen \
  -Dspring-boot.run.jvmArguments="\
    -Ddatagen.count=50000 \
    -Ddatagen.batch-size=200 \
    -Ddatagen.attacker-ip-count=5"
```

**Configuration properties (`application-datagen.yml`):**

| Property | Default | Description |
|---|---|---|
| `datagen.count` | `10000` | Total events to generate |
| `datagen.batch-size` | `100` | Events per POST request |
| `datagen.target-url` | `http://localhost:8080` | Base URL of the Mini WSA instance |
| `datagen.attacker-ip-count` | `3` | Number of IPs designated as repeat attackers |
| `datagen.wave-seed-size` | `7` | Seed events per attacker IP in Phase 1 |

**After the generator finishes, verify results:**
```bash
curl -s "http://localhost:8080/v1/stats/summary?\
from=$(date -u -v-2d +%Y-%m-%dT%H:%M:%SZ)\
&to=$(date -u +%Y-%m-%dT%H:%M:%SZ)" | jq '{totalEvents, avgThreatScore, byAction}'
```

---

## Running the Tests

Tests require Docker (Testcontainers spins up a real PostgreSQL container for the integration test).

```bash
# All tests (unit + integration)
./mvnw test

# Unit tests only (no Docker required)
./mvnw test -Dtest="ClassificationServiceTest,ThreatScoringServiceTest,RepeatOffenderDetectionServiceTest,ValidationServiceTest"

# Integration test only
./mvnw test -Dtest="EventIngestionControllerIT"
```

**Test coverage:**

| Test class | Type | What it verifies |
|---|---|---|
| `ClassificationServiceTest` | Unit | Each `rule.category` maps to the correct `attackType` string |
| `ThreatScoringServiceTest` | Unit | All additive score components and the 100-point cap |
| `RepeatOffenderDetectionServiceTest` | Unit | `isRepeatOffender` returns `true`/`false` based on mocked DB count |
| `ValidationServiceTest` | Unit | Required-field and enum-value validation; valid events pass through |
| `EventIngestionControllerIT` | Integration (Testcontainers) | Full stack: ingest → enrich → persist → stats API reflects events |

---

## Project Structure

```
src/main/java/com/akamai/miniwsa/
├── MiniwsaApplication.java
├── config/
│   ├── GlobalExceptionHandler.java   (409 duplicate handling)
│   └── JpaConfig.java
├── domain/
│   └── SecurityEvent.java            (JPA entity)
├── repository/
│   └── SecurityEventRepository.java
├── ingestion/
│   ├── controller/EventIngestionController.java
│   ├── dto/                          (SecurityEventRequest, IngestionResponse, ...)
│   ├── mapper/SecurityEventMapper.java
│   └── service/
│       ├── IngestionService.java     (shared by REST + Kafka paths)
│       └── ValidationService.java
├── enrichment/service/
│   ├── EnrichmentService.java
│   ├── ClassificationService.java
│   ├── ThreatScoringService.java
│   └── RepeatOffenderDetectionService.java
├── stats/
│   ├── controller/StatsController.java
│   ├── dto/                          (SummaryResponse, CategoryStats, ...)
│   └── service/StatsService.java     (JdbcTemplate native SQL)
├── samples/
│   ├── controller/SamplesController.java
│   ├── dto/                          (SamplesResponse, SecurityEventResponse)
│   └── service/SamplesService.java   (dynamic WHERE + pagination)
└── datagen/                          (active only under 'datagen' profile)
    ├── DataGeneratorConfig.java
    ├── DataGeneratorRunner.java
    ├── EventFactory.java
    └── IngestionClient.java

src/main/resources/
├── application.yml
├── application-datagen.yml
└── db/migration/
    ├── V1__init.sql                  (schema + indexes)
    └── V2__add_rule_message.sql
```
