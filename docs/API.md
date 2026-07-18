# API Reference — Mini WSA

All endpoints are served on `http://localhost:8080` by default.

---

## Table of Contents

1. [Conventions](#conventions)
2. [POST /v1/events/ingest](#post-v1eventsingest)
3. [GET /v1/stats/summary](#get-v1statssummary)
4. [GET /v1/events/samples](#get-v1eventssamples)
5. [Error Responses](#error-responses)
6. [Rate Limiting](#rate-limiting)

---

## Conventions

### Timestamps

All timestamps use **ISO-8601 UTC** format: `YYYY-MM-DDTHH:MM:SSZ`

```
2026-07-16T10:00:00Z      ✔
2026-07-16T10:00:00.000Z  ✔
2026-07-16 10:00:00       ✘ — missing T and Z
```

### Required vs. Optional fields

Fields marked **required** fail validation if absent or blank.
Optional fields may be omitted entirely from the request body.

### Enumerations

All enum values are **case-sensitive** in the request body.
Query parameter filters (`category`, `action`) are **case-insensitive** — `injection` and `INJECTION` both match.

---

## POST /v1/events/ingest

Ingests one event (JSON object) or a batch of events (JSON array) in a single request.

**Atomicity:** the entire batch succeeds or fails as a unit. A single invalid event rejects the whole batch and nothing is persisted.

```
POST /v1/events/ingest
Content-Type: application/json
```

### Request body fields

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `eventId` | string | **yes** | non-blank, globally unique | Idempotency key — re-sending the same `eventId` returns 400 |
| `timestamp` | string | **yes** | ISO-8601 UTC | Client-reported event time (stored as `event_timestamp`) |
| `configId` | number | **yes** | integer ≥ 1 | WAF configuration / tenant identifier |
| `clientIp` | string | **yes** | non-blank | Source IP of the HTTP request being inspected |
| `policyId` | string | no | — | Optional WAF policy identifier |
| `hostname` | string | no | — | Hostname of the protected origin |
| `path` | string | no | — | Request URI path |
| `method` | string | no | — | HTTP method (`GET`, `POST`, etc.) |
| `statusCode` | number | no | integer | HTTP response status code |
| `userAgent` | string | no | — | `User-Agent` header value |
| `rule` | object | **yes** | see sub-fields | Matched WAF rule |
| `rule.id` | string | no | — | Rule identifier |
| `rule.name` | string | no | — | Human-readable rule name |
| `rule.message` | string | no | — | Rule description |
| `rule.severity` | string | **yes** | `CRITICAL` \| `HIGH` \| `MEDIUM` \| `LOW` | Affects threat score (+40/+30/+20/+10) |
| `rule.category` | string | **yes** | `INJECTION` \| `XSS` \| `PROTOCOL_VIOLATION` \| `DATA_LEAKAGE` \| `BOT` \| `DOS` \| `RATE_LIMIT` | Attack category, maps to `attackType` |
| `action` | string | **yes** | `DENY` \| `ALERT` \| `MONITOR` | WAF action taken; affects threat score (+20/+10/+0) |
| `geoLocation` | object | no | — | |
| `geoLocation.country` | string | no | — | ISO 3166-1 alpha-2 country code |
| `geoLocation.city` | string | no | — | City name |
| `requestSize` | number | no | integer | Size of the original HTTP request in bytes |
| `responseSize` | number | no | integer | Size of the HTTP response in bytes |

### Response body (201 Created)

```json
{
  "ingested": 1,
  "failed":   0,
  "errors":   []
}
```

| Field | Type | Description |
|---|---|---|
| `ingested` | number | Number of events successfully persisted |
| `failed` | number | Number of events rejected (0 on success) |
| `errors` | array | Validation errors — empty on success |
| `errors[].eventIndex` | number | Zero-based index of the failed event in the batch |
| `errors[].field` | string | Field that failed validation |
| `errors[].message` | string | Human-readable reason |

---

### Examples

#### Single event — success

```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":   "evt-001",
    "timestamp": "2026-07-16T10:00:00Z",
    "configId":  14227,
    "clientIp":  "203.0.113.42",
    "hostname":  "www.example.com",
    "path":      "/admin/login",
    "method":    "POST",
    "statusCode": 403,
    "userAgent": "Mozilla/5.0 (compatible; attacker/1.0)",
    "rule": {
      "id":       "950001",
      "name":     "SQL_INJECTION",
      "message":  "SQL Injection Detected",
      "severity": "CRITICAL",
      "category": "INJECTION"
    },
    "action": "DENY",
    "geoLocation": {
      "country": "CN",
      "city":    "Beijing"
    },
    "requestSize":  1024,
    "responseSize": 256
  }' | jq .
```

```json
{
  "ingested": 1,
  "failed":   0,
  "errors":   []
}
```

#### Batch of events — success

```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '[
    {
      "eventId":   "evt-100",
      "timestamp": "2026-07-16T10:01:00Z",
      "configId":  14227,
      "clientIp":  "198.51.100.17",
      "path":      "/api/search",
      "method":    "GET",
      "rule": { "severity": "HIGH", "category": "BOT" },
      "action":    "ALERT"
    },
    {
      "eventId":   "evt-101",
      "timestamp": "2026-07-16T10:01:05Z",
      "configId":  14227,
      "clientIp":  "10.0.0.5",
      "path":      "/public/assets/logo.png",
      "method":    "GET",
      "rule": { "severity": "LOW", "category": "RATE_LIMIT" },
      "action":    "MONITOR"
    }
  ]' | jq .
```

```json
{
  "ingested": 2,
  "failed":   0,
  "errors":   []
}
```

#### Validation failure — missing required fields

```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":   "evt-bad",
    "timestamp": "2026-07-16T10:00:00Z",
    "configId":  14227,
    "rule": { "severity": "HIGH", "category": "BOT" }
  }' | jq .
```

```json
{
  "ingested": 0,
  "failed":   1,
  "errors": [
    { "eventIndex": 0, "field": "clientIp", "message": "clientIp is required" },
    { "eventIndex": 0, "field": "action",   "message": "action is required"   }
  ]
}
```

#### Validation failure — invalid enum values

```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":   "evt-bad2",
    "timestamp": "2026-07-16T10:00:00Z",
    "configId":  14227,
    "clientIp":  "10.0.0.1",
    "rule": { "severity": "EXTREME", "category": "HACKING" },
    "action":    "KICK"
  }' | jq .
```

```json
{
  "ingested": 0,
  "failed":   1,
  "errors": [
    { "eventIndex": 0, "field": "rule.severity", "message": "rule.severity must be one of: CRITICAL, HIGH, MEDIUM, LOW" },
    { "eventIndex": 0, "field": "rule.category", "message": "rule.category must be one of: INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT" },
    { "eventIndex": 0, "field": "action",         "message": "action must be one of: DENY, ALERT, MONITOR" }
  ]
}
```

#### Duplicate eventId — 400 Conflict

```bash
# First POST succeeds
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{ "eventId": "evt-001", ... }' | jq .
# → { "ingested": 1, "failed": 0, "errors": [] }

# Second POST with the same eventId is rejected
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '{ "eventId": "evt-001", ... }' | jq .
```

```json
{
  "message": "Batch rejected: one or more events already exist (duplicate eventId)"
}
```

#### Empty batch — 400

```bash
curl -s -X POST http://localhost:8080/v1/events/ingest \
  -H "Content-Type: application/json" \
  -d '[]' | jq .
```

```json
{
  "ingested": 0,
  "failed":   0,
  "errors": [
    { "eventIndex": -1, "field": "body", "message": "Batch must contain at least one event" }
  ]
}
```

---

## GET /v1/stats/summary

Returns aggregated analytics over a time window.

```
GET /v1/stats/summary?from=<iso8601>&to=<iso8601>[&configId=<id>]
```

### Query parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from` | string | **yes** | Start of time window (ISO-8601 UTC, inclusive) |
| `to` | string | **yes** | End of time window (ISO-8601 UTC, inclusive) |
| `configId` | number | no | Filter to a specific tenant; omit to aggregate all |

### Response body (200 OK)

```json
{
  "configId":    14227,
  "timeRange":   { "from": "2026-07-16T00:00:00Z", "to": "2026-07-16T23:59:59Z" },
  "totalEvents": 1250,
  "byCategory": {
    "INJECTION": { "count": 512, "avgThreatScore": 72.4 },
    "BOT":       { "count": 310, "avgThreatScore": 38.1 },
    "XSS":       { "count": 428, "avgThreatScore": 55.0 }
  },
  "byAction": {
    "DENY":    800,
    "ALERT":   320,
    "MONITOR": 130
  },
  "topAttackers": [
    { "clientIp": "203.0.113.42", "count": 245, "avgThreatScore": 89.3 },
    { "clientIp": "198.51.100.17","count": 180, "avgThreatScore": 74.6 }
  ],
  "topTargetedPaths": [
    { "path": "/admin/login", "count": 320 },
    { "path": "/api/search",  "count": 210 }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `configId` | number \| null | Echoes the `configId` filter; null if none was provided |
| `timeRange` | object | Echoes the `from`/`to` bounds |
| `totalEvents` | number | Total events matching the filter |
| `byCategory` | object | Map of `category → { count, avgThreatScore }` |
| `byAction` | object | Map of `action → count` |
| `topAttackers` | array | Top 10 source IPs by event count, with average threat score |
| `topTargetedPaths` | array | Top 10 targeted paths by event count |

---

### Examples

#### Summary for a specific tenant and day

```bash
curl -s "http://localhost:8080/v1/stats/summary?\
configId=14227\
&from=2026-07-16T00:00:00Z\
&to=2026-07-16T23:59:59Z" | jq .
```

#### Cross-tenant summary (all configIds)

```bash
curl -s "http://localhost:8080/v1/stats/summary?\
from=2026-07-16T00:00:00Z\
&to=2026-07-16T23:59:59Z" | jq .
```

#### Last 24 hours (using shell date arithmetic)

```bash
FROM=$(date -u -v-24H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
    || date -u -d '24 hours ago' +%Y-%m-%dT%H:%M:%SZ)  # macOS / Linux
TO=$(date -u +%Y-%m-%dT%H:%M:%SZ)

curl -s "http://localhost:8080/v1/stats/summary?from=$FROM&to=$TO" | jq .
```

#### Validation error — missing required params

```bash
curl -s "http://localhost:8080/v1/stats/summary" | jq .
```

```json
{ "message": "Required parameter 'from' is missing" }
```

#### Validation error — `from` after `to`

```bash
curl -s "http://localhost:8080/v1/stats/summary?\
from=2026-07-17T00:00:00Z\
&to=2026-07-16T00:00:00Z" | jq .
```

```json
{ "message": "'from' must not be after 'to'" }
```

---

## GET /v1/events/samples

Returns a paginated list of raw events matching the given filters, ordered by `event_timestamp DESC`.

```
GET /v1/events/samples[?configId=<id>][&from=<iso8601>][&to=<iso8601>]
                       [&category=<cat>][&action=<act>]
                       [&limit=<n>][&offset=<n>]
```

### Query parameters

| Parameter | Type | Required | Default | Constraints | Description |
|---|---|---|---|---|---|
| `configId` | number | no | — | — | Filter by tenant |
| `from` | string | no | — | ISO-8601 UTC | Earliest `event_timestamp` (inclusive) |
| `to` | string | no | — | ISO-8601 UTC | Latest `event_timestamp` (inclusive) |
| `category` | string | no | — | case-insensitive | Filter by `rule.category` (e.g. `injection` or `INJECTION`) |
| `action` | string | no | — | case-insensitive | Filter by WAF action (e.g. `deny` or `DENY`) |
| `limit` | number | no | `20` | 1 – 100 | Page size |
| `offset` | number | no | `0` | ≥ 0 | Number of rows to skip |

### Response body (200 OK)

```json
{
  "total": 1250,
  "data": [
    {
      "eventId":        "evt-001",
      "eventTimestamp": "2026-07-16T10:00:00Z",
      "receivedAt":     "2026-07-16T10:00:00.123Z",
      "configId":       14227,
      "policyId":       null,
      "clientIp":       "203.0.113.42",
      "hostname":       "www.example.com",
      "path":           "/admin/login",
      "method":         "POST",
      "statusCode":     403,
      "userAgent":      "Mozilla/5.0 (compatible; attacker/1.0)",
      "ruleId":         "950001",
      "ruleName":       "SQL_INJECTION",
      "ruleSeverity":   "CRITICAL",
      "ruleCategory":   "INJECTION",
      "action":         "DENY",
      "geoCountry":     "CN",
      "geoCity":        "Beijing",
      "requestSize":    1024,
      "responseSize":   256,
      "attackType":     "SQL Injection",
      "threatScore":    75
    }
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `total` | number | Total matching rows (across all pages) |
| `data` | array | Events on the current page |
| `data[].eventTimestamp` | string | Client-reported event time |
| `data[].receivedAt` | string | Server-assigned ingestion time |
| `data[].attackType` | string | Derived from `rule.category` by the classification service |
| `data[].threatScore` | number | Computed 0–100; see threat score formula in README |

---

### Examples

#### First page, default limit

```bash
curl -s "http://localhost:8080/v1/events/samples" | jq .
```

#### Filter by category and action (case-insensitive)

```bash
curl -s "http://localhost:8080/v1/events/samples?\
category=injection\
&action=deny\
&limit=5" | jq .
```

#### Filter by tenant and time window

```bash
curl -s "http://localhost:8080/v1/events/samples?\
configId=14227\
&from=2026-07-16T00:00:00Z\
&to=2026-07-16T23:59:59Z\
&limit=20\
&offset=0" | jq .
```

#### Paginate through results

```bash
# Page 1
curl -s "http://localhost:8080/v1/events/samples?limit=20&offset=0" | jq .

# Page 2
curl -s "http://localhost:8080/v1/events/samples?limit=20&offset=20" | jq .

# Page 3
curl -s "http://localhost:8080/v1/events/samples?limit=20&offset=40" | jq .
```

#### High-threat events only (score-based filtering via jq)

```bash
# No native score filter — retrieve and filter client-side
curl -s "http://localhost:8080/v1/events/samples?limit=100" \
  | jq '.data[] | select(.threatScore >= 70)'
```

#### Validation errors

```bash
# limit out of range
curl -s "http://localhost:8080/v1/events/samples?limit=0" | jq .
# → { "message": "'limit' must be between 1 and 100" }

curl -s "http://localhost:8080/v1/events/samples?limit=101" | jq .
# → { "message": "'limit' must be between 1 and 100" }

# negative offset
curl -s "http://localhost:8080/v1/events/samples?offset=-1" | jq .
# → { "message": "'offset' must be >= 0" }

# bad timestamp
curl -s "http://localhost:8080/v1/events/samples?from=yesterday" | jq .
# → { "message": "Invalid timestamp format — use ISO-8601 (e.g. 2026-05-20T14:32:10Z)" }

# from after to
curl -s "http://localhost:8080/v1/events/samples?\
from=2026-07-17T00:00:00Z\
&to=2026-07-16T00:00:00Z" | jq .
# → { "message": "'from' must not be after 'to'" }
```

---

## Error Responses

All error responses share the same JSON shape:

```json
{ "message": "Human-readable description" }
```

For validation errors on `POST /v1/events/ingest`, the shape is the full `IngestionResponse`:

```json
{
  "ingested": 0,
  "failed":   2,
  "errors": [
    { "eventIndex": 0, "field": "clientIp", "message": "clientIp is required" },
    { "eventIndex": 1, "field": "action",   "message": "action is required"   }
  ]
}
```

### HTTP status codes

| Status | Meaning |
|---|---|
| `200 OK` | Successful read |
| `201 Created` | All events ingested successfully |
| `400 Bad Request` | Validation failure, malformed JSON, or duplicate `eventId` |
| `429 Too Many Requests` | Rate limit exceeded |
| `500 Internal Server Error` | Unexpected server error |

---

## Rate Limiting

Limits are enforced per client IP by a Redis-backed servlet filter.
The client IP is resolved from `X-Forwarded-For` (first value), falling back to `remoteAddr`.

| Endpoint | Limit |
|---|---|
| `POST /v1/events/ingest` | 1 000 requests / minute |
| `GET /v1/stats/summary` | 100 requests / minute |
| `GET /v1/events/samples` | 100 requests / minute |

When the limit is exceeded the server responds:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/json

{"message":"Rate limit exceeded. Max 100 requests per minute. Try again in 60 seconds."}
```

The filter **fails open** — if Redis is unavailable, requests are passed through without counting.
