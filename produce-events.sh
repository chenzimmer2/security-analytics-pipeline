#!/usr/bin/env bash
# Kafka producer script for Mini WSA.
#
# Sends sample security events to the 'security-events' topic.
# Each message is a single JSON event (same schema as POST /v1/events/ingest).
#
# Usage:
#   ./produce-events.sh           # sends 5 sample events
#   ./produce-events.sh 20        # sends 20 events
#
# Prerequisites: docker compose up kafka (or any reachable Kafka at localhost:9093)

set -euo pipefail

TOPIC="security-events"
BROKER="localhost:9093"
COUNT="${1:-5}"

CATEGORIES=("INJECTION" "XSS" "PROTOCOL_VIOLATION" "DATA_LEAKAGE" "BOT" "DOS" "RATE_LIMIT")
SEVERITIES=("CRITICAL" "HIGH" "MEDIUM" "LOW")
ACTIONS=("DENY" "ALERT" "MONITOR")
IPS=("203.0.113.42" "198.51.100.17" "185.234.219.4" "172.16.0.1" "10.0.0.50")
PATHS=("/api/v1/login" "/admin/dashboard" "/search" "/api/v1/users" "/checkout")
RULE_IDS=("950001" "950002" "950003" "950004" "950005")

events=()
for i in $(seq 1 "$COUNT"); do
    category="${CATEGORIES[$((RANDOM % ${#CATEGORIES[@]}))]}"
    severity="${SEVERITIES[$((RANDOM % ${#SEVERITIES[@]}))]}"
    action="${ACTIONS[$((RANDOM % ${#ACTIONS[@]}))]}"
    ip="${IPS[$((RANDOM % ${#IPS[@]}))]}"
    path="${PATHS[$((RANDOM % ${#PATHS[@]}))]}"
    rule_id="${RULE_IDS[$((RANDOM % ${#RULE_IDS[@]}))]}"
    event_id="evt-script-$(date +%s%N)-${i}"
    timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

    # Single-line JSON — kafka-console-producer sends one message per line
    event=$(printf '{"eventId":"%s","timestamp":"%s","configId":14227,"policyId":"pol_web1","clientIp":"%s","hostname":"www.example.com","path":"%s","method":"POST","statusCode":403,"userAgent":"Mozilla/5.0 (compatible; producer-script/1.0)","rule":{"id":"%s","name":"%s_RULE","message":"Attack detected","severity":"%s","category":"%s"},"action":"%s","geoLocation":{"country":"US","city":"New York"},"requestSize":%d,"responseSize":%d}' \
        "$event_id" "$timestamp" "$ip" "$path" \
        "$rule_id" "$category" \
        "$severity" "$category" \
        "$action" \
        $((RANDOM % 4096 + 128)) $((RANDOM % 512 + 64)))

    events+=("$event")
done

echo "Sending $COUNT event(s) to topic '$TOPIC' on $BROKER ..."
echo ""

printf '%s\n' "${events[@]}" | \
    docker run --rm -i --network host \
        confluentinc/cp-kafka:7.6.1 \
        kafka-console-producer \
            --bootstrap-server "$BROKER" \
            --topic "$TOPIC"

echo ""
echo "Done. Check the app logs or run:"
echo "  curl -s 'http://localhost:8080/v1/stats/summary?from=$(date -u -v-5M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%SZ)&to=$(date -u +%Y-%m-%dT%H:%M:%SZ)' | jq '{totalEvents, byAction}'"
