#!/usr/bin/env bash
# Verifies rate limiting on the stats and samples APIs.
# Flushes Redis first so the test always starts from a clean window.
#
# Usage: ./test-rate-limit.sh

set -euo pipefail

BASE_URL="http://localhost:8080"
STATS_URL="$BASE_URL/v1/stats/summary?from=2026-07-16T00:00:00Z&to=2026-07-16T23:59:59Z"
SAMPLES_URL="$BASE_URL/v1/events/samples?limit=1"

echo "=== Rate Limit Test ==="
echo ""

# ── Reset Redis window ────────────────────────────────────────────────────────
echo "Flushing Redis (clean window)..."
docker exec miniwsa-redis redis-cli FLUSHALL > /dev/null
echo ""

# ── Stats API — expect 100 × 200, then 429 ───────────────────────────────────
echo "Testing GET /v1/stats/summary  (limit: 100 req/min)"
echo -n "Sending 105 requests: "

ok=0; blocked=0
for i in $(seq 1 105); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$STATS_URL")
    if   [ "$code" = "200" ]; then ok=$((ok + 1));      echo -n "."
    elif [ "$code" = "429" ]; then blocked=$((blocked + 1)); echo -n "X"
    else echo -n "?"
    fi
done
echo ""
echo "  200 OK:             $ok  (expected 100)"
echo "  429 Too Many Req:   $blocked  (expected 5)"

if [ "$ok" -eq 100 ] && [ "$blocked" -eq 5 ]; then
    echo "  PASS ✓"
else
    echo "  FAIL ✗"
fi
echo ""

# ── Reset again for samples test ─────────────────────────────────────────────
docker exec miniwsa-redis redis-cli FLUSHALL > /dev/null

# ── Samples API — same read tier, same limit ─────────────────────────────────
echo "Testing GET /v1/events/samples  (same read tier — limit: 100 req/min)"
echo -n "Sending 105 requests: "

ok=0; blocked=0
for i in $(seq 1 105); do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$SAMPLES_URL")
    if   [ "$code" = "200" ]; then ok=$((ok + 1));      echo -n "."
    elif [ "$code" = "429" ]; then blocked=$((blocked + 1)); echo -n "X"
    else echo -n "?"
    fi
done
echo ""
echo "  200 OK:             $ok  (expected 100)"
echo "  429 Too Many Req:   $blocked  (expected 5)"

if [ "$ok" -eq 100 ] && [ "$blocked" -eq 5 ]; then
    echo "  PASS ✓"
else
    echo "  FAIL ✗"
fi
echo ""

# ── Show current Redis state ──────────────────────────────────────────────────
echo "Redis keys after test:"
docker exec miniwsa-redis redis-cli KEYS "rate_limit:*"
echo ""
echo "Done. Redis resets automatically after 60 seconds."
