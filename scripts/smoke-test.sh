#!/bin/sh
# End-to-end check against the running docker-compose stack: create a campaign, launch it,
# and wait until every delivery has an outcome (delivered or failed after retries).
set -eu

API="${API:-http://localhost:8081}"

wait_for() {
  url="$1"; tries=60
  until curl -fs "$url" > /dev/null; do
    tries=$((tries - 1)); [ "$tries" -gt 0 ] || { echo "timeout waiting for $url"; exit 1; }
    sleep 2
  done
}

wait_for "$API/actuator/health/readiness"
wait_for "http://localhost:8082/actuator/health/readiness"

id=$(curl -fs -X POST "$API/api/campaigns" -H 'Content-Type: application/json' -d '{
  "name": "Smoke test",
  "template": "Hi {{name}}",
  "channels": ["EMAIL", "SMS"],
  "recipients": [
    {"name": "Alice", "email": "alice@example.com", "phone": "+8801700000000"},
    {"name": "Bob",   "email": "bob@example.com"},
    {"name": "Eve",   "email": "eve@example.com", "phone": "12345"},
    {"name": "Flaky", "email": "flaky@flaky.example"}
  ]
}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "created campaign $id"

curl -fs -X POST "$API/api/campaigns/$id/launch" > /dev/null
echo "launched, waiting for completion..."

tries=60
while :; do
  body=$(curl -fs "$API/api/campaigns/$id")
  case "$body" in *'"status":"COMPLETED"'*) echo "$body"; break ;; esac
  tries=$((tries - 1)); [ "$tries" -gt 0 ] || { echo "campaign did not complete: $body"; exit 1; }
  sleep 2
done

# 6 deliveries: Alice x2, Bob, Eve x2 (SMS invalid), Flaky (fails after retries).
echo "$body" | grep -q '"expectedDeliveries":6' || { echo "unexpected delivery count"; exit 1; }
echo "smoke test passed"
