# Enrichment Design — Mini WSA

This document describes the classification and threat scoring pipeline that runs on every ingested security event.

---

## Pipeline Overview

```
Raw DLR (validated)
        │
        ▼
IngestionService
  │  sets receivedAt = Instant.now()  (server-side only)
  │
  ▼
EnrichmentService
  ├─▶ ClassificationService      →  attackType  (string, no I/O)
  └─▶ ThreatScoringService
          └─▶ RepeatOffenderDetectionService  →  isRepeat (boolean)
              └─▶ COUNT query on received_at column
        │
        ▼
Enriched entity:
  { original DLR fields + attackType + threatScore + receivedAt }
        │
        ▼
SecurityEventRepository.save()  →  PostgreSQL
```

`EnrichmentService` orchestrates the pipeline. It receives the validated request plus the server-assigned `receivedAt`, calls the two sub-services, and returns the enriched entity ready to persist. It does not assign `receivedAt` — that is always the responsibility of `IngestionService`.

---

## Step 1: Classification (`ClassificationService`)

Maps `rule.category` to a human-readable `attackType` string. Pure function — no I/O, no state.

| rule.category | attackType |
|---|---|
| `INJECTION` | `SQL/Command Injection` |
| `XSS` | `Cross-Site Scripting` |
| `PROTOCOL_VIOLATION` | `Protocol Anomaly` |
| `DATA_LEAKAGE` | `Data Exfiltration` |
| `BOT` | `Bot Activity` |
| `DOS` | `Denial of Service` |
| `RATE_LIMIT` | `Rate Limiting` |

**Implementation:** A `Map<RuleCategory, String>` constant. `classify(category)` performs a single map lookup and returns the string.

**Testing:** Unit test covers all 7 mappings. Unknown categories are rejected upstream by `ValidationService` before `ClassificationService` is called.

---

## Step 2: Threat Score Computation (`ThreatScoringService`)

Computes an integer `threatScore` in the range `[0, 100]`.

### Formula

```
threatScore =
    severityScore(rule.severity)
  + actionScore(action)
  + pathBonus(path)
  + repeatOffenderBonus(clientIp, receivedAt)

capped at 100
```

### Component Breakdown

**Severity score** (from `rule.severity`):

| Severity | Points |
|---|---|
| `CRITICAL` | 40 |
| `HIGH` | 30 |
| `MEDIUM` | 20 |
| `LOW` | 10 |

**Action score** (from `action`):

| Action | Points |
|---|---|
| `DENY` | +20 |
| `ALERT` | +10 |
| `MONITOR` | +0 |

**Path bonus** (from `path`):
- If `path` contains `/admin` or `/login` → +15
- Otherwise → +0

**Repeat offender bonus** (from `RepeatOffenderDetectionService`):
- If the same `clientIp` has more than 5 events in the last 10 minutes (measured by `received_at`) → +15
- Otherwise → +0

**Cap:**
```java
Math.min(score, 100)
```

### Score Range Examples

| Scenario | Severity | Action | Path bonus | Repeat bonus | Raw score | Final score |
|---|---|---|---|---|---|---|
| Maximum possible | CRITICAL (40) | DENY (20) | +15 | +15 | 90 | 90 |
| High threat, no repeat | CRITICAL (40) | DENY (20) | +15 | +0 | 75 | 75 |
| Medium bot traffic | HIGH (30) | ALERT (10) | +0 | +0 | 40 | 40 |
| Low-severity monitor | LOW (10) | MONITOR (0) | +0 | +0 | 10 | 10 |
| Path hit, repeat offender | MEDIUM (20) | DENY (20) | +15 | +15 | 70 | 70 |

Note: the theoretical maximum is 40+20+15+15 = 90. The cap of 100 is a defensive guard for future scoring additions.

### Persisted Fields

After enrichment, the stored entity contains:
- All original DLR fields (eventId, eventTimestamp, configId, policyId, clientIp, hostname, path, method, statusCode, userAgent, rule.*, action, geoLocation.*, requestSize, responseSize)
- `attackType` — derived by ClassificationService
- `threatScore` — computed by ThreatScoringService
- `receivedAt` — assigned by IngestionService (server-side only)

### Testing Strategy

`ThreatScoringService` is tested with a mocked `RepeatOffenderDetectionService`:
- Test each severity level independently (4 cases).
- Test each action level independently (3 cases).
- Test path bonus: path contains `/admin`, path contains `/login`, path contains neither.
- Test repeat-offender bonus: mock returns `true`, mock returns `false`.
- Test cap: construct inputs that sum above 100, verify final score is exactly 100.

---

## Step 3: Repeat Offender Detection (`RepeatOffenderDetectionService`)

### Responsibility

Determine whether a given `clientIp` has had more than 5 events **received** within the last 10 minutes.

### Implementation

```java
public boolean isRepeatOffender(String clientIp) {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM security_events " +
        "WHERE client_ip = ? AND received_at > NOW() - INTERVAL '10 minutes'",
        Long.class,
        clientIp
    );
    return count != null && count > 5;
}
```

### Why `received_at` and not `event_timestamp`

| Criterion | `event_timestamp` | `received_at` |
|---|---|---|
| Source | Client (external) | Server (internal) |
| Tamper-resistant | No — attacker can backdate | Yes — server-controlled |
| Reflects actual traffic load | No | Yes |
| Correct for security decisions | No | Yes |

Using `event_timestamp` for the repeat-offender window would allow an attacker to submit events with past or future timestamps and bypass detection. `received_at` measures when the system actually absorbed the traffic — which is what the repeat-offender bonus is designed to detect.

### Index Used

```sql
CREATE INDEX idx_security_events_client_ip_received_at
    ON security_events (client_ip, received_at);
```

PostgreSQL performs an index range scan: equality on `client_ip` (highly selective), then range on `received_at`. This is fast regardless of total table size.

### Correctness Guarantees

- **Correct after restarts** — state lives in the DB, not JVM memory.
- **Correct across multiple instances** — all instances query the same PostgreSQL.
- **Tamper-resistant** — uses `received_at` (server-assigned), not the client-provided timestamp.

### Off-by-One Note

The repeat-offender check runs *before* the current event is saved. This means at the time of the check, a 6th event from an IP will see a count of 5, so the bonus applies starting from the **7th** event. This is an acceptable minor subtlety. If strict semantics are required, the check can use `>= 5`.

### Performance Tradeoffs

| Approach | Correctness | Restart-safe | Multi-instance | Complexity |
|---|---|---|---|---|
| DB COUNT on `received_at` (current) | High | Yes | Yes | Low |
| In-memory `ConcurrentHashMap` | Lower (lost on restart, per-instance) | No | No | Medium |
| Redis sorted set | High | Yes | Yes | Medium |
| Kafka Streams / Flink | High | Yes | Yes | High |

**Current choice:** DB COUNT query. Simple, correct, no extra infrastructure. One extra query per ingested event — negligible at 10k events, documented as a scaling concern.

**Future improvement:** Redis sorted sets — O(log n) per event, sub-millisecond, correct across instances, with automatic TTL-based eviction:
```
ZADD  repeat:{ip}  {received_at_ms}  {event_id}
ZREMRANGEBYSCORE  repeat:{ip}  0  {10_min_ago_ms}
ZCARD  repeat:{ip}
```
