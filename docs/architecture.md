# Architecture Overview — Mini WSA

## Style: Modular Monolith

Mini WSA is a single deployable Spring Boot application. Internally it is organized into clearly bounded modules (packages) with explicit dependencies. There are no distributed components in the core implementation; the bonus features (Kafka, Redis) are additive and isolated to their own packages.

---

## Dual Timestamps

Every security event carries two timestamps:

| Field | Source | Column | Purpose |
|---|---|---|---|
| `timestamp` (input) | Client / event source | `event_timestamp TIMESTAMPTZ` | When the security event occurred (analytics filtering, sorting) |
| `receivedAt` | Server — set on ingestion | `received_at TIMESTAMPTZ` | When the backend received the event (repeat-offender detection, audit) |

`receivedAt` is always set server-side using `Instant.now()` at the moment `IngestionService` processes the event. It is never read from the request body. This prevents client clock manipulation from affecting server-side security decisions.

---

## Component Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                    Mini WSA — Spring Boot App                    │
│                                                                  │
│  ┌─────────────────┐   ┌──────────────────────────────────────┐  │
│  │  RateLimitFilter│   │  Ingestion                           │  │
│  │  (bonus)        │──▶│  EventIngestionController            │  │
│  │  Redis per-IP   │   │  KafkaConsumer (bonus)               │  │
│  └─────────────────┘   │  ValidationService                   │  │
│                         │  IngestionService                    │  │
│                         └──────────────┬─────────────────────┘  │
│                                        │                         │
│                         ┌──────────────▼─────────────────────┐  │
│                         │  Enrichment                         │  │
│                         │  EnrichmentService (orchestrator)   │  │
│                         │  ClassificationService (stateless)  │  │
│                         │  ThreatScoringService (stateless)   │  │
│                         │  RepeatOffenderDetectionService      │  │
│                         │    └─▶ COUNT on received_at         │  │
│                         └──────────────┬─────────────────────┘  │
│                                        │                         │
│              ┌─────────────────────────▼──────────────────┐     │
│              │         SecurityEventRepository             │     │
│              │         (Spring Data JPA + JdbcTemplate)    │     │
│              └──────────────────────┬─────────────────────┘     │
│                                     │                            │
│  ┌────────────────────┐  ┌──────────▼────────────────────────┐  │
│  │  Analytics         │  │                                   │  │
│  │  StatsController   │  │           PostgreSQL               │  │
│  │  StatsService      │  │                                   │  │
│  │  (JdbcTemplate)    │  └───────────────────────────────────┘  │
│  └────────────────────┘                                          │
│  ┌────────────────────┐                                          │
│  │  Samples           │                                          │
│  │  SamplesController │                                          │
│  │  SamplesService    │                                          │
│  └────────────────────┘                                          │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

External infrastructure (bonus):
  ┌────────────┐    ┌────────────────┐
  │   Redis    │    │  Kafka Broker  │
  │  (rate     │    │  (async event  │
  │  limiting) │    │   ingestion)   │
  └────────────┘    └────────────────┘
```

---

## Request Flow

### REST Ingestion — Single Event

```
POST /v1/events/ingest
Body: { single SecurityEventRequest }
        │
        ▼
RateLimitFilter (bonus — ingestion limit: 1000 req/min per IP)
        │
        ▼
EventIngestionController
  └── normalizes single event to a list of one
        │
        ▼
IngestionService.ingestAll(List<SecurityEventRequest>)
  ├── ValidationService.validate(event)
  │     ├── passes  → continue
  │     └── fails   → collect error, skip event
  ├── Sets receivedAt = Instant.now()  (server-side only)
  ├── EnrichmentService.enrich(event, receivedAt)
  │     ├── ClassificationService   → attackType
  │     └── ThreatScoringService
  │           └── RepeatOffenderDetectionService
  │                 └── COUNT WHERE client_ip = ? AND received_at > NOW() - 10 min
  └── SecurityEventRepository.save(enrichedEntity)
        ├── success → count ingested
        └── DataIntegrityViolationException (UNIQUE event_id) → 409 Conflict

201 Created  →  { ingested: N, failed: M, errors: [...] }
409 Conflict →  { message: "Event already ingested: evt-00132" }
400 Bad Request  →  { errors: [...] }
```

### REST Ingestion — Batch of Events

```
POST /v1/events/ingest
Body: [ SecurityEventRequest, ... ]
        │
        ▼
EventIngestionController
  └── receives list directly
        │
        ▼
IngestionService.ingestAll(List<SecurityEventRequest>)
  └── same loop as single-event path:
      for each event: validate → set receivedAt → enrich → save
      collect per-event results (success or error)

201 Created  →  { ingested: N, failed: M, errors: [...] }
```

Single and batch events share exactly one code path in `IngestionService`. The controller normalizes a single event to a one-element list before calling the service.

### Kafka Ingestion (Bonus)

```
Kafka topic: security-events
        │
        ▼
SecurityEventKafkaConsumer
  └── deserializes as SecurityEventRequest
        │
        ▼
IngestionService.ingestAll(List.of(event))
  └── same pipeline as REST (validate → enrich → save)
```

The Kafka consumer calls `IngestionService` directly with the same method signature as the REST path. No duplication of business logic.

### Stats / Samples — Read Path

```
GET /v1/stats/summary  |  GET /v1/events/samples
        │
        ▼
RateLimitFilter (bonus — analytics limit: 100 req/min per IP)
  └── Redis INCR per IP → if > 100/min → 429 with Retry-After: 60
        │
        ▼
StatsController / SamplesController
        │
        ▼
StatsService / SamplesService
  └── JdbcTemplate native SQL
      filtered by event_timestamp (analytics time window)
        │
        ▼
PostgreSQL
```

---

## Infrastructure Stack

| Component | Technology | Role |
|---|---|---|
| Application | Spring Boot 3.x (Java 21) | Monolith runtime |
| Database | PostgreSQL 16 | Persistent event storage + aggregations |
| Message broker | Apache Kafka 7.x (bonus) | Async event ingestion topic |
| Rate-limit store | Redis 7 (bonus) | Per-IP sliding-window counters |
| Migrations | Flyway | Schema versioning |
| Containerization | Docker Compose | Local full-stack setup |

The core application (ingestion + enrichment + analytics + samples) works without Kafka and Redis. Both bonus components start cleanly only when their Spring profiles are active and the infrastructure is present.

---

## Module Dependency Rules

```
ingestion  →  enrichment  →  repository
analytics  →  repository
samples    →  repository
ratelimit  →  (Redis only — no domain dependency)
datagen    →  ingestion (via HTTP client, not direct call)
```

Each module only imports from modules below it. `enrichment` does not import from `ingestion` or `analytics`. This keeps the dependency graph acyclic and every module independently testable.
