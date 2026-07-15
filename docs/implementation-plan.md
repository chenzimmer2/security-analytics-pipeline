# Implementation Plan — Mini WSA

This document breaks down the implementation into ordered milestones. Each milestone corresponds to a git tag and a working, committable state of the system.

---

## Assignment Requirement Checklist

This table maps every stated assignment requirement to where it is implemented.

### Part 1 — Ingestion

| Requirement | Implementation |
|---|---|
| Accept a single event | `EventIngestionController` normalizes single input to `List.of(event)`, calls `IngestionService.ingestAll()` |
| Accept a batch of events (array) | `EventIngestionController` receives `List<SecurityEventRequest>` directly, calls `IngestionService.ingestAll()` |
| Validate required fields | `ValidationService` with Jakarta `@NotBlank`, `@NotNull` annotations |
| Validate enum values (`rule.category`, `rule.severity`, `action`) | Custom `@ValidEnum` annotation checked in `ValidationService` |
| Validate timestamp format (ISO-8601) | `@NotNull` + `Instant` deserialization in `SecurityEventRequest` — Jackson rejects malformed timestamps |
| Return HTTP 201 for success | `EventIngestionController` returns `ResponseEntity.status(201).body(IngestionResponse)` |
| Return HTTP 400 with validation details | `ValidationService` returns structured error list; controller wraps in `400` if all events failed |
| Assign server-side `receivedAt` | `IngestionService.ingestAll()` sets `receivedAt = Instant.now()` before calling enrichment |

### Part 2 — Classification & Enrichment

| Requirement | Implementation |
|---|---|
| Map `rule.category` → `attackType` string | `ClassificationService` — static Map, pure function |
| Compute `threatScore` (severity component) | `ThreatScoringService` — `severityScore()` method |
| Compute `threatScore` (action component) | `ThreatScoringService` — `actionScore()` method |
| Compute `threatScore` (path `/admin` or `/login` bonus) | `ThreatScoringService` — `pathBonus()` method |
| Compute `threatScore` (repeat offender bonus, >5 events / 10 min) | `RepeatOffenderDetectionService` — `COUNT` query on `(client_ip, received_at)` |
| Cap `threatScore` at 100 | `ThreatScoringService` — `Math.min(score, 100)` |
| Store enriched event with original fields + `attackType` + `threatScore` + `receivedAt` | `IngestionService` maps enriched result to `SecurityEvent` entity and calls `repository.save()` |

### Part 3 — Stats API

| Requirement | Implementation |
|---|---|
| `GET /v1/stats/summary?configId=&from=&to=` | `StatsController` + `StatsService` |
| `totalEvents` | `SELECT COUNT(*)` |
| `byCategory` with count and avgThreatScore | `SELECT rule_category, COUNT(*), AVG(threat_score) GROUP BY rule_category` |
| `byAction` with count | `SELECT action, COUNT(*) GROUP BY action` |
| `topAttackers` (top 10 by event count) | `SELECT client_ip, COUNT(*), AVG(threat_score) GROUP BY client_ip ORDER BY COUNT(*) DESC LIMIT 10` |
| `topTargetedPaths` (top 10 by event count) | `SELECT path, COUNT(*) GROUP BY path ORDER BY COUNT(*) DESC LIMIT 10` |
| `configId` optional — aggregate all if omitted | Dynamic WHERE clause in `StatsService` |

### Part 4 — Samples API

| Requirement | Implementation |
|---|---|
| `GET /v1/events/samples` with filters | `SamplesController` + `SamplesService` |
| `configId`, `from`, `to`, `category`, `action` filters (all optional) | Dynamic WHERE clause built in `SamplesService` |
| Pagination via `limit` and `offset` | `LIMIT ? OFFSET ?` in SQL; default limit 20, max 100 |
| Sorted by timestamp descending | `ORDER BY event_timestamp DESC` |
| Response includes total count | Two-query approach: `COUNT(*)` then `SELECT *` with same WHERE clause |

### Part 5 — Data Generator

| Requirement | Implementation |
|---|---|
| Generate realistic random security events | `DataGeneratorRunner implements ApplicationRunner` |
| Simulate attack waves (bursts from same IP) | Fixed pool of ~20 IPs; 3 designated "attacker" IPs generate burst events within a 10-min window |
| Configurable event count | `application.yml` property `datagen.count`, default 10000 |
| Output can be fed into ingestion API | `DataGeneratorRunner` POSTs batches to `POST /v1/events/ingest` |

---

## Milestone Overview

| Tag | Scope | Status |
|---|---|---|
| `v0.1-ingestion` | Project scaffold + POST ingest endpoint | pending |
| `v0.2-enrichment` | Classification, threat scoring, repeat-offender detection | pending |
| `v0.3-stats` | Stats summary API | pending |
| `v0.4-samples` | Samples API with filtering + pagination | pending |
| `v0.5-datagen` | Data generator module | pending |
| `v0.6-tests` | Unit + integration tests | pending |
| `v0.7-kafka` | Kafka consumer (bonus) | pending |
| `v0.8-ratelimit` | Redis-backed rate limiting (bonus) | pending |
| `v1.0-final` | README, architecture diagram, polish | pending |

---

## v0.1 — Project Scaffold + Ingestion

**Goal:** A running Spring Boot app that accepts single and batch events, validates them, persists them (without enrichment yet), and handles duplicates with 409.

### Tasks

1. Initialize Maven project (`pom.xml`)
   - Spring Boot 3.x parent
   - Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `flyway-core`, `postgresql`, `lombok`
   - Test dependencies: `spring-boot-starter-test`, `testcontainers`

2. Write `application.yml`
   - DataSource config (PostgreSQL)
   - Flyway enabled
   - Logging config

3. Write Flyway migration `V1__init.sql`

   ```sql
   CREATE TABLE security_events (
       id             BIGSERIAL PRIMARY KEY,
       event_id       VARCHAR(100) UNIQUE NOT NULL,
       event_timestamp TIMESTAMPTZ NOT NULL,
       received_at    TIMESTAMPTZ NOT NULL,
       config_id      BIGINT NOT NULL,
       policy_id      VARCHAR(100),
       client_ip      VARCHAR(45) NOT NULL,
       hostname       VARCHAR(255),
       path           TEXT,
       method         VARCHAR(10),
       status_code    INTEGER,
       user_agent     TEXT,
       rule_id        VARCHAR(50),
       rule_name      VARCHAR(100),
       rule_severity  VARCHAR(20),
       rule_category  VARCHAR(50),
       action         VARCHAR(20),
       geo_country    VARCHAR(10),
       geo_city       VARCHAR(100),
       request_size   INTEGER,
       response_size  INTEGER,
       attack_type    VARCHAR(100),
       threat_score   INTEGER
   );

   -- For stats/samples: filter by config + time of event
   CREATE INDEX idx_events_config_time
       ON security_events (config_id, event_timestamp);

   -- For repeat-offender detection: filter by IP + time received
   CREATE INDEX idx_events_ip_received
       ON security_events (client_ip, received_at);

   -- For category filtering in samples + stats
   CREATE INDEX idx_events_category_time
       ON security_events (rule_category, event_timestamp);

   -- For action filtering in samples + stats
   CREATE INDEX idx_events_action_time
       ON security_events (action, event_timestamp);
   ```

4. Define `SecurityEvent` JPA entity
   - Map all columns including `eventTimestamp` (`@Column(name = "event_timestamp")`) and `receivedAt` (`@Column(name = "received_at")`)
   - `attackType` and `threatScore` included (nullable until v0.2)

5. Define `SecurityEventRequest` DTO with Jakarta Bean Validation
   - `@NotBlank`: `eventId`, `clientIp`, `hostname`, `path`, `method`
   - `@NotNull`: `timestamp` (the incoming field name), `configId`, `rule`, `action`, `geoLocation`
   - Custom `@ValidEnum` for `rule.severity`, `rule.category`, `action`
   - `Instant` type on `timestamp` — Jackson rejects malformed ISO-8601 automatically

6. Implement `ValidationService`
   - Input: single `SecurityEventRequest`
   - Output: `List<ValidationError>` (empty = valid)
   - Uses Jakarta Validator programmatically so it works outside web context (reusable by Kafka consumer)

7. Implement `IngestionService.ingestAll(List<SecurityEventRequest>)`
   - For each event: validate → if valid, assign `receivedAt = Instant.now()`, map to entity, save
   - On `DataIntegrityViolationException` from repository: record as 409 duplicate in errors
   - Returns `IngestionResult` (ingested count, failed count, error list)

8. Implement `EventIngestionController`
   - `POST /v1/events/ingest`
   - Accepts both `SecurityEventRequest` and `List<SecurityEventRequest>` — use two `@RequestBody` method overloads, or deserialize via `JsonNode` and detect array/object
   - Normalizes single event to `List.of(event)` before calling `IngestionService`
   - If all events failed validation: `400` with error list
   - If at least one succeeded: `201` with `IngestionResponse { ingested, failed, errors }`
   - If all failed due to duplicate `event_id`: `409` with message (for single-event case)

9. Write `docker-compose.yml` with `postgres:16`

10. Verify:
    - Single event → 201
    - Invalid event → 400 with field errors
    - Batch with one invalid → 201 with errors array
    - Duplicate `eventId` → 409

**Commit message style:** `feat: add event ingestion endpoint with schema validation`

---

## v0.2 — Enrichment

**Goal:** Every valid ingested event is classified and scored before persistence.

### Tasks

1. Implement `ClassificationService`
   - `String classify(RuleCategory category)` — static `EnumMap<RuleCategory, String>` lookup
   - No I/O, no state

2. Implement `RepeatOffenderDetectionService`
   - `boolean isRepeatOffender(String clientIp)` via `JdbcTemplate`:
     ```sql
     SELECT COUNT(*) FROM security_events
     WHERE client_ip = ? AND received_at > NOW() - INTERVAL '10 minutes'
     ```
   - Returns `count > 5`
   - Note: check runs before saving current event, so strictly it applies from the 7th event onward

3. Implement `ThreatScoringService`
   - `int score(SecurityEventRequest event, boolean isRepeat)`
   - Applies formula: severity + action + path bonus + repeat bonus, capped at 100
   - Pure function — no I/O beyond the `isRepeat` parameter

4. Implement `EnrichmentService`
   - `EnrichedFields enrich(SecurityEventRequest event, Instant receivedAt)`
   - Calls: `classify()` → `isRepeatOffender()` → `score()`
   - Returns: `{ attackType, threatScore }`
   - Does not call repository directly; does not assign `receivedAt` (passed in from caller)

5. Wire `EnrichmentService` into `IngestionService` (between validation and persist)

6. Verify with curl: ingest an event → query the DB → confirm `attack_type` and `threat_score` are populated

**Commit message style:** `feat: add classification and threat scoring enrichment pipeline`

---

## v0.3 — Stats Summary API

**Goal:** `GET /v1/stats/summary` returns correct aggregated statistics.

### Tasks

1. Define `SummaryResponse` DTO and nested types:
   - `CategoryStats { count, avgThreatScore }`
   - `AttackerStats { clientIp, count, avgThreatScore }`
   - `PathStats { path, count }`

2. Implement `StatsService` using `JdbcTemplate`

   All queries share a helper that builds an optional WHERE clause from `configId`, `from`, `to`:
   ```sql
   -- base filter (added dynamically if params present)
   WHERE config_id = :configId               -- if configId provided
     AND event_timestamp >= :from            -- if from provided
     AND event_timestamp <= :to              -- if to provided
   ```

   Individual queries:
   ```sql
   -- totalEvents
   SELECT COUNT(*) FROM security_events [WHERE ...]

   -- byCategory
   SELECT rule_category, COUNT(*), AVG(threat_score)
   FROM security_events [WHERE ...]
   GROUP BY rule_category

   -- byAction
   SELECT action, COUNT(*)
   FROM security_events [WHERE ...]
   GROUP BY action

   -- topAttackers (top 10)
   SELECT client_ip, COUNT(*), AVG(threat_score)
   FROM security_events [WHERE ...]
   GROUP BY client_ip
   ORDER BY COUNT(*) DESC
   LIMIT 10

   -- topTargetedPaths (top 10)
   SELECT path, COUNT(*)
   FROM security_events [WHERE ...]
   GROUP BY path
   ORDER BY COUNT(*) DESC
   LIMIT 10
   ```

   All run in `@Transactional(readOnly = true)`.

3. Implement `StatsController`
   - `GET /v1/stats/summary`
   - `configId` optional; `from`/`to` optional; validated as `Instant`

4. Verify: ingest 50 events → call stats → confirm counts and averages match

**Why DB aggregation over application-level aggregation:**
- Moving GROUP BY / AVG / COUNT to the DB layer means only a small result set is returned to the application, not thousands of raw rows.
- The index on `(config_id, event_timestamp)` makes the time-range filter efficient.

**Commit message style:** `feat: add stats summary API with SQL aggregations`

---

## v0.4 — Samples API

**Goal:** `GET /v1/events/samples` returns paginated, filtered enriched event records.

### Tasks

1. Define `SamplesResponse { total, data: List<SecurityEventResponse> }`
   - `SecurityEventResponse` mirrors all entity fields including `eventTimestamp`, `receivedAt`, `attackType`, `threatScore`

2. Implement `SamplesService` using `JdbcTemplate`
   - Dynamic WHERE clause from optional params: `configId`, `from`, `to`, `category`, `action`
   - Time range filters use `event_timestamp` (analytics: when the attack occurred)
   - Two queries with the same WHERE clause:
     - `SELECT COUNT(*)` → populates `total` for pagination metadata
     - `SELECT * ... ORDER BY event_timestamp DESC LIMIT ? OFFSET ?` → populates `data`
   - Cap `limit` at 100; default 20
   - Use `RowMapper<SecurityEventResponse>`

3. Implement `SamplesController`
   - `GET /v1/events/samples`
   - Validate: `limit` in `[1, 100]`, `offset >= 0`

4. Verify: ingest varied events → query with different filter combinations → confirm `total` count and paginated results

**Commit message style:** `feat: add samples API with dynamic filtering and pagination`

---

## v0.5 — Data Generator

**Goal:** A runnable module that produces realistic test data and posts it to the ingestion API.

### Tasks

1. Implement `DataGeneratorRunner implements ApplicationRunner`
   - Activated by Spring profile `datagen`
   - Config via `application-datagen.yml`:
     - `datagen.count` (default 10000)
     - `datagen.batch-size` (default 100)
     - `datagen.target-url` (default `http://localhost:8080`)
   - IP pool: 20 random IPs, 3 designated as "attackers"
   - Attacker IPs: generate 20+ events within consecutive 10-minute windows (to trigger repeat-offender bonus)
   - Random field generation:
     - `eventId`: UUID
     - `timestamp`: random within last 24 hours (use unique times per batch to avoid false duplicates)
     - `configId`: one of 3 fixed values
     - `hostname`, `path`: realistic paths including `/api/v1/login`, `/admin/dashboard`
     - `rule.category`, `rule.severity`, `action`: weighted random (e.g., INJECTION more common)
   - Posts batches to `POST /v1/events/ingest`

2. Verify: run profile → 10,000 events in DB → run stats API → confirm non-zero counts in all categories

**Commit message style:** `feat: add data generator with attack wave simulation`

---

## v0.6 — Tests

**Goal:** Sufficient test coverage for interview confidence and correctness validation.

### Unit Tests

| Class | Cases |
|---|---|
| `ClassificationService` | All 7 category → attackType mappings |
| `ThreatScoringService` | Each severity level (4 cases), each action (3 cases), path bonus on/off (2 cases), repeat bonus on/off (2 cases), cap at 100 (1 case) |
| `RepeatOffenderDetectionService` | Mock JdbcTemplate: count = 0 → false, count = 5 → false, count = 6 → true |
| `ValidationService` | Missing `eventId`, missing `clientIp`, invalid `rule.category` enum, invalid `action` enum, malformed timestamp |

### Integration Tests

| Test | Scenario |
|---|---|
| `EventIngestionControllerIT` | POST single valid event → 201 + ingested=1 |
| `EventIngestionControllerIT` | POST single invalid event (missing clientIp) → 400 + error details |
| `EventIngestionControllerIT` | POST batch with 2 valid + 1 invalid → 201 + ingested=2, failed=1, errors=[...] |
| `EventIngestionControllerIT` | POST duplicate eventId → 409 |
| `StatsControllerIT` | Ingest 10 known events → GET summary → verify totalEvents, byCategory, byAction counts |

**Test infrastructure:** `@SpringBootTest` + Testcontainers (`PostgreSQLContainer`). No H2 — tests run against real PostgreSQL to avoid dialect surprises with `INTERVAL`, `TIMESTAMPTZ`, etc.

**Commit message style:** `test: add unit tests for enrichment and integration tests for API`

---

## v0.7 — Kafka Consumer (Bonus)

**Goal:** Events published to a Kafka topic are processed through the same pipeline as REST ingestion.

### Tasks

1. Add dependency: `spring-kafka`; update `docker-compose.yml` with Kafka + Zookeeper

2. Add `KafkaConfig`
   - Consumer group: `mini-wsa-group`
   - Topic: `security-events`
   - Value deserializer: `JsonDeserializer<SecurityEventRequest>`

3. Implement `SecurityEventKafkaConsumer`
   ```java
   @KafkaListener(topics = "security-events", groupId = "mini-wsa-group")
   public void consume(SecurityEventRequest event) {
       ingestionService.ingestAll(List.of(event));
   }
   ```
   - On validation failure: log warning and skip (Dead Letter Queue as future improvement)
   - On duplicate `eventId`: log info and skip (idempotent by design)

4. Write `KafkaProducerRunner` (activated by profile `kafka-producer`) for manual testing

5. Verify: produce 10 events to topic → confirm they appear in DB → confirm enrichment applied

**Commit message style:** `feat: add Kafka consumer for async event ingestion`

---

## v0.8 — Rate Limiting (Bonus)

**Goal:** Ingestion and analytics endpoints are protected by tiered Redis-backed rate limits.

### Tasks

1. Add dependency: `spring-boot-starter-data-redis`; add `redis:7` to `docker-compose.yml`

2. Add `RedisConfig` (Lettuce connection factory, `StringRedisTemplate`)

3. Implement `RateLimitFilter implements Filter`

   Logic:
   ```
   key   = "rate_limit:{tier}:{ip}"
   count = INCR key
   if count == 1: EXPIRE key 60
   if count > limit: return 429, Retry-After: 60
   ```

   Tiers:
   | Endpoint pattern | Tier key | Limit |
   |---|---|---|
   | `POST /v1/events/ingest` | `ingest` | 1000 req/min |
   | `GET /v1/stats/**` | `read` | 100 req/min |
   | `GET /v1/events/samples` | `read` | 100 req/min |

   - Extract IP from `X-Forwarded-For` header, fallback to `request.getRemoteAddr()`
   - Register via `FilterRegistrationBean` scoped to `/*` (tier determined by path inside filter)

4. Verify:
   - Loop 101 GET requests to `/v1/stats/summary` → first 100 succeed, 101st returns 429
   - Loop 1001 POST requests to `/v1/events/ingest` → first 1000 succeed, 1001st returns 429

**Commit message style:** `feat: add Redis-backed tiered rate limiting`

---

## v1.0 — Final Polish

**Goal:** Project is ready for submission and interview walkthrough.

### Tasks

1. Write `README.md`:
   - Project description and architecture overview
   - How to build and run (`docker-compose up` + curl examples for all endpoints)
   - Storage choice justification
   - What you would improve with more time
   - Which parts were challenging and how you approached them

2. Verify `docker-compose up` brings up the full stack cleanly from a fresh clone (no manual steps)

3. Run data generator → run stats API → confirm counts look realistic

4. Tag `v1.0-final`

---

## Dependency Notes

- `v0.2` depends on `v0.1` (entity + repository must exist before enrichment wiring).
- `v0.3` and `v0.4` depend on `v0.2` (enriched events must be in DB for meaningful stats/samples).
- `v0.5` depends on `v0.1` (needs the ingest endpoint live).
- `v0.6` can be written alongside `v0.3`–`v0.4` or after; both unit and integration tests are independent of datagen.
- `v0.7` and `v0.8` are independent of each other; both require the core to be complete and tested.
