# Design Decisions — Mini WSA

This document explains the key design choices made in the Mini WSA system, including the reasoning behind each decision and the alternatives that were considered.

---

## 1. Modular Monolith over Microservices

**Decision:** Single deployable Spring Boot application with clear internal package boundaries.

**Reasoning:**
- The assignment scope is a single-team project with well-understood domain boundaries.
- Microservices add operational overhead (service discovery, distributed tracing, network latency, independent deployments) that is disproportionate for this scope.
- A modular monolith provides the same internal separation of concerns with far less complexity.
- If the system needs to scale beyond a single instance, specific modules (e.g., enrichment or analytics) can be extracted into separate services at that point.

---

## 2. Two Timestamps: `eventTimestamp` and `receivedAt`

**Decision:** Store two separate timestamps on every security event.

| Field | Source | DB Column | Meaning |
|---|---|---|---|
| `eventTimestamp` | Client (from DLR `timestamp` field) | `event_timestamp TIMESTAMPTZ` | When the security event occurred |
| `receivedAt` | Server — set in `IngestionService` | `received_at TIMESTAMPTZ` | When the backend ingested the event |

**Reasoning:**
- The assignment explicitly requires: *"Assigns a server-side receivedAt timestamp to each event on ingestion."*
- Client-provided timestamps may be incorrect, delayed, or manipulated. Security analytics must not rely on them for internal decisions.
- `receivedAt` is used for the repeat-offender detection window (measuring real ingestion activity).
- `eventTimestamp` is used for analytics queries (filtering by when the attack occurred).

**Naming in code:**
- The input DTO uses `timestamp` (matching the spec JSON field).
- The Java entity and DB column use `eventTimestamp` / `event_timestamp` to be explicit.
- `receivedAt` is never read from the request body. It is always set server-side using `Instant.now()`.

---

## 3. PostgreSQL as the Primary Storage Engine

**Decision:** Store all enriched security events in PostgreSQL.

**Reasoning:**
- Native `TIMESTAMPTZ` columns enable efficient range queries — the dominant access pattern (filter by `configId` + time window).
- `GROUP BY`, `AVG`, `COUNT` aggregations run at the DB layer without pulling rows into the application.
- ACID guarantees ensure the `event_id` UNIQUE constraint reliably prevents duplicate ingestion.
- Works naturally with Spring Data JPA for entity management and `JdbcTemplate` for complex analytics queries.
- Trivial Docker setup; well-understood operationally.

**Alternatives considered:**
- **ClickHouse**: Significantly faster at 100M+ row column-oriented aggregations, but adds operational complexity (separate query language, separate driver, no ACID writes). Disproportionate for this scope.
- **MongoDB**: Flexible schema is unnecessary here since the DLR schema is fixed. Aggregation pipeline is more verbose than SQL for this use case.
- **TimescaleDB**: Good fit for time-series (would add hypertables and continuous aggregates), but the core PostgreSQL features are sufficient at this scale. Mentioned as a future improvement path.

---

## 4. JdbcTemplate for Analytics Queries

**Decision:** Use `JdbcTemplate` with native SQL for Stats and Samples APIs; use Spring Data JPA only for single-entity lifecycle operations (save, findById).

**Reasoning:**
- JPA is designed for entity lifecycle management, not bulk aggregation. Using JPA for `GROUP BY` / `AVG` / `COUNT` requires either native queries or JPQL projections — both end up being close to raw SQL anyway.
- `JdbcTemplate` provides a thin, transparent layer over JDBC with direct SQL control — easier to understand, optimize, and explain at an interview.
- Avoids N+1 problems that JPA can introduce when traversing relationships in large result sets.

---

## 5. RepeatOffenderDetectionService via DB Query on `received_at`

**Decision:** Detect repeat offenders by issuing a `COUNT(*)` query filtered by `client_ip` and a 10-minute window on `received_at`.

```sql
SELECT COUNT(*) FROM security_events
WHERE client_ip = ?
  AND received_at > NOW() - INTERVAL '10 minutes'
```

**Why `received_at` and not `event_timestamp`:**
- Client-provided timestamps (`event_timestamp`) may be incorrect, delayed, or deliberately manipulated.
- The repeat-offender check measures real ingestion activity — how many events has the system actually received from this IP in the last 10 minutes?
- Using `received_at` makes this tamper-resistant and consistent with how the server actually sees traffic.

**Why DB query and not in-memory cache:**
- **Correctness after restarts**: An in-memory cache (`ConcurrentHashMap<IP, Deque<Instant>>`) loses its state on application restart. The DB query is always correct regardless of restarts.
- **Correctness across instances**: A monolith may run multiple instances in production. In-memory state is not shared.
- **Simplicity**: No extra infrastructure, no cache eviction logic, no thread-safety complexity.
- **Index-backed**: The `(client_ip, received_at)` composite index makes this an efficient range scan.

**Tradeoffs accepted:**
- One additional DB query per ingested event. At assignment scale (10k events) this is negligible.
- At production scale, this would become a bottleneck. Future improvement: Redis sorted sets:
  ```
  ZADD repeat:{ip} {received_at_ms} {event_id}
  ZREMRANGEBYSCORE repeat:{ip} 0 {10_min_ago_ms}
  ZCARD repeat:{ip}
  ```
  Or a dedicated streaming aggregation layer (Flink, Kafka Streams).

---

## 6. Separated ValidationService

**Decision:** Validation logic lives in a dedicated `ValidationService`, not in the controller or the ingestion service.

**Reasoning:**
- Both REST and Kafka ingestion paths must validate events before processing. Centralizing validation in one service avoids code duplication.
- The controller handles HTTP concerns (request/response mapping) and should not contain business-level validation.
- `ValidationService` can be unit-tested independently without spinning up a web context.

---

## 7. Stateless ClassificationService and ThreatScoringService

**Decision:** Both services are Spring `@Service` beans with no mutable state. `ClassificationService` has no I/O. `ThreatScoringService` depends only on `RepeatOffenderDetectionService` (injected).

**Reasoning:**
- Stateless services are trivially unit-testable — no mocking of caches or shared state.
- Can be called safely from multiple threads without synchronization.
- `ThreatScoringService` can be tested with a mocked `RepeatOffenderDetectionService` to cover all score combinations.

---

## 8. Shared Ingestion Pipeline for Single Events and Batches

**Decision:** `IngestionService` exposes one method — `ingestAll(List<SecurityEventRequest>)`. The controller normalizes a single event to a one-element list before calling it.

**Reasoning:**
- Single and batch events go through exactly the same business logic path: validate → set `receivedAt` → enrich → save.
- No duplication. No separate code paths. No separate batch logic.
- Partial success semantics: valid events in a batch are persisted even if others fail validation.
- The response always includes `ingested` count and `errors` array, regardless of batch or single input.

**Batch failure behavior:** The entire batch is never rejected because of one invalid event. Each event is processed and validated independently. Results are aggregated into a single response.

---

## 9. HTTP 409 Conflict for Duplicate Event IDs

**Decision:** If a `POST /v1/events/ingest` request contains an `eventId` that already exists in the database, the service returns `HTTP 409 Conflict`.

**Reasoning:**
- `event_id` is a unique identity for a security event. Ingesting the same event twice would corrupt analytics (double-counted threat scores, inflated attacker counts).
- The database enforces `UNIQUE(event_id)`. When a duplicate is detected, PostgreSQL raises a `DataIntegrityViolationException` which is caught and translated to a 409.
- 409 clearly communicates "resource already exists" and is semantically correct for this case.
- 400 would be wrong — the request format is valid; the conflict is at the data level.

**In batch mode:** If one event in a batch is a duplicate, the others are still processed. The duplicate is reported in the `errors` array with a 409 reason.

---

## 10. Redis for Rate Limiting (Bonus), not In-Memory

**Decision:** Rate limiting uses Redis `INCR` + `EXPIRE` per IP key, not an in-memory `ConcurrentHashMap` or a JVM-local library like Bucket4j.

**Reasoning:**
- Rate-limit state is temporary (TTL = 60 s), write-heavy, and must survive application restarts.
- In-memory counters do not work correctly when the application runs on more than one instance — each instance would independently allow up to the full limit, multiplying effective throughput by the instance count.
- Redis operations (`INCR`, `EXPIRE`) are O(1) and add sub-millisecond latency per request.

**Why rate limiting is a bonus, not core:**
- The assignment specification does not require rate limiting as a core feature.
- The core application must work without Redis.
- Rate limiting is isolated in `RateLimitFilter` — removing it requires no changes to any service or controller.

---

## 11. Tiered Rate Limits: Ingestion vs Analytics

**Decision:** Apply different rate limits to the ingestion endpoint and the analytics endpoints.

| Endpoint | Limit | Reasoning |
|---|---|---|
| `POST /v1/events/ingest` | 1000 req/min per IP | Ingestion comes from automated sources (CDN nodes, edge devices). A stricter limit would drop legitimate traffic. |
| `GET /v1/stats/summary` | 100 req/min per IP | Analytics queries are user-initiated. 100/min is generous for dashboard polling. |
| `GET /v1/events/samples` | 100 req/min per IP | Same reasoning as stats. Pagination means repeated calls, but 100/min covers normal usage. |

Redis key structure:
- `rate_limit:ingest:{ip}` (TTL 60 s, limit 1000)
- `rate_limit:read:{ip}` (TTL 60 s, limit 100)

---

## 12. Kafka as an Additional Ingestion Path (Bonus)

**Decision:** `SecurityEventKafkaConsumer` consumes from topic `security-events` and calls `IngestionService.ingestAll()` — the exact same method used by the REST controller.

**Reasoning:**
- The processing pipeline (validate → enrich → persist) is independent of the transport mechanism.
- Reusing `IngestionService` means no duplicated business logic.
- Idempotency is handled by the `event_id` UNIQUE constraint — Kafka's at-least-once delivery cannot cause double-counting. Duplicates are rejected at the DB layer.
- This models a realistic production pattern where edge nodes publish to Kafka and the analytics backend consumes asynchronously.

---

## 13. Flyway for Schema Management

**Decision:** Use Flyway for database migrations (`V1__init.sql`, etc.).

**Reasoning:**
- Schema changes are version-controlled and applied deterministically on startup.
- Works natively with Spring Boot auto-configuration.
- Makes the `docker-compose up` workflow fully automatic: schema is applied before the application accepts traffic.
