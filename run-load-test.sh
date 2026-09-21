#!/usr/bin/env bash
# ============================================================================
# run-load-test.sh — End-to-end distributed rate limiter verification
#
# Brings up the full stack (Redis + 3 app instances + Nginx), runs load tests,
# prints PASS/FAIL, and tears down. Run from the project root:
#
#   chmod +x run-load-test.sh && ./run-load-test.sh
# ============================================================================

set -euo pipefail

CAPACITY=10
TOTAL_REQUESTS=50
RESULTS_DIR="results"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log()  { echo -e "${YELLOW}▸ $1${NC}"; }
pass() { echo -e "${GREEN}✅ PASS: $1${NC}"; }
fail() { echo -e "${RED}❌ FAIL: $1${NC}"; }

mkdir -p "$RESULTS_DIR"

# ── Cleanup on exit ────────────────────────────────────────────
cleanup() {
    log "Tearing down Docker Compose stack..."
    docker compose down --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

# ── Step 1: Build and start the stack ──────────────────────────
log "Building and starting the stack (Redis + 3 app instances + Nginx)..."
docker compose up -d --build --wait

log "Stack is up. Verifying all containers are healthy..."
docker compose ps

# ── Step 2: Flush Redis (clean slate) ──────────────────────────
log "Flushing Redis to ensure clean state..."
docker compose exec redis redis-cli FLUSHALL

# ── Step 3: Distributed test (through Nginx → 3 instances) ────
log ""
log "═══════════════════════════════════════════════════════════"
log "  TEST 1: DISTRIBUTED — $TOTAL_REQUESTS requests through Nginx → 3 instances"
log "  Configured capacity: $CAPACITY"
log "═══════════════════════════════════════════════════════════"
log ""

docker run --rm \
    --network distlimit_default \
    -v "$(pwd)/loadtest:/scripts" \
    -e TARGET_URL=http://nginx:80 \
    -e CLIENT_ID=distributed-test-client \
    grafana/k6:latest run /scripts/load-test.js \
    --summary-export=/scripts/dist-summary.json \
    2>&1 | tee "$RESULTS_DIR/distributed-test-output.txt"

# Copy summary from mounted volume
cp loadtest/dist-summary.json "$RESULTS_DIR/distributed-test-summary.json" 2>/dev/null || true

# Extract allowed count from k6 output
DIST_ALLOWED=$(grep -oP 'allowed_requests[^:]*:\s*\K[0-9]+' "$RESULTS_DIR/distributed-test-output.txt" | head -1 || echo "0")
DIST_REJECTED=$(grep -oP 'rejected_requests[^:]*:\s*\K[0-9]+' "$RESULTS_DIR/distributed-test-output.txt" | head -1 || echo "0")

echo ""
echo "  Distributed test results:"
echo "    Requests sent:  $TOTAL_REQUESTS"
echo "    Allowed (200):  $DIST_ALLOWED"
echo "    Rejected (429): $DIST_REJECTED"
echo "    Expected:       $CAPACITY allowed"
echo ""

# ── Step 4: Flush Redis and run single-instance control test ───
log "Flushing Redis for control test..."
docker compose exec redis redis-cli FLUSHALL

log ""
log "═══════════════════════════════════════════════════════════"
log "  TEST 2: SINGLE INSTANCE — $TOTAL_REQUESTS requests → app1 only"
log "  Configured capacity: $CAPACITY (same as distributed test)"
log "═══════════════════════════════════════════════════════════"
log ""

docker run --rm \
    --network distlimit_default \
    -v "$(pwd)/loadtest:/scripts" \
    -e TARGET_URL=http://app1:8080 \
    -e CLIENT_ID=single-test-client \
    grafana/k6:latest run /scripts/load-test.js \
    --summary-export=/scripts/single-summary.json \
    2>&1 | tee "$RESULTS_DIR/single-instance-test-output.txt"

cp loadtest/single-summary.json "$RESULTS_DIR/single-instance-test-summary.json" 2>/dev/null || true

SINGLE_ALLOWED=$(grep -oP 'allowed_requests[^:]*:\s*\K[0-9]+' "$RESULTS_DIR/single-instance-test-output.txt" | head -1 || echo "0")
SINGLE_REJECTED=$(grep -oP 'rejected_requests[^:]*:\s*\K[0-9]+' "$RESULTS_DIR/single-instance-test-output.txt" | head -1 || echo "0")

echo ""
echo "  Single-instance test results:"
echo "    Requests sent:  $TOTAL_REQUESTS"
echo "    Allowed (200):  $SINGLE_ALLOWED"
echo "    Rejected (429): $SINGLE_REJECTED"
echo "    Expected:       $CAPACITY allowed"
echo ""

# ── Step 5: Summary and PASS/FAIL ─────────────────────────────
log ""
log "═══════════════════════════════════════════════════════════"
log "  FINAL SUMMARY"
log "═══════════════════════════════════════════════════════════"
echo ""
echo "  ┌────────────────────┬────────┬──────────┬──────────┐"
echo "  │ Test               │ Sent   │ Allowed  │ Rejected │"
echo "  ├────────────────────┼────────┼──────────┼──────────┤"
printf "  │ Distributed (3x)   │ %-6s │ %-8s │ %-8s │\n" "$TOTAL_REQUESTS" "$DIST_ALLOWED" "$DIST_REJECTED"
printf "  │ Single instance    │ %-6s │ %-8s │ %-8s │\n" "$TOTAL_REQUESTS" "$SINGLE_ALLOWED" "$SINGLE_REJECTED"
echo "  └────────────────────┴────────┴──────────┴──────────┘"
echo "  Configured capacity: $CAPACITY"
echo ""

OVERALL_PASS=true

if [ "$DIST_ALLOWED" = "$CAPACITY" ]; then
    pass "Distributed test: exactly $CAPACITY allowed (matches capacity)"
else
    fail "Distributed test: $DIST_ALLOWED allowed, expected $CAPACITY"
    OVERALL_PASS=false
fi

if [ "$SINGLE_ALLOWED" = "$CAPACITY" ]; then
    pass "Single-instance test: exactly $CAPACITY allowed (matches capacity)"
else
    fail "Single-instance test: $SINGLE_ALLOWED allowed, expected $CAPACITY"
    OVERALL_PASS=false
fi

if [ "$DIST_ALLOWED" = "$SINGLE_ALLOWED" ]; then
    pass "Both tests produced identical results — distributing changed nothing"
else
    fail "Distributed ($DIST_ALLOWED) != Single ($SINGLE_ALLOWED) — variance detected"
    OVERALL_PASS=false
fi

echo ""
if [ "$OVERALL_PASS" = true ]; then
    echo -e "${GREEN}══════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  ALL TESTS PASSED — distributed rate limiting is correct${NC}"
    echo -e "${GREEN}══════════════════════════════════════════════════════════${NC}"
else
    echo -e "${RED}══════════════════════════════════════════════════════════${NC}"
    echo -e "${RED}  SOME TESTS FAILED — check results above${NC}"
    echo -e "${RED}══════════════════════════════════════════════════════════${NC}"
fi

# Save final summary
cat > "$RESULTS_DIR/summary.txt" << EOF
DistLimit Load Test Results
===========================
Date: $(date)
Capacity: $CAPACITY
Total requests per test: $TOTAL_REQUESTS

Distributed test (Nginx → 3 instances):
  Allowed:  $DIST_ALLOWED
  Rejected: $DIST_REJECTED
  Match:    $([ "$DIST_ALLOWED" = "$CAPACITY" ] && echo "YES" || echo "NO")

Single-instance test (app1 only):
  Allowed:  $SINGLE_ALLOWED
  Rejected: $SINGLE_REJECTED
  Match:    $([ "$SINGLE_ALLOWED" = "$CAPACITY" ] && echo "YES" || echo "NO")

Overall: $([ "$OVERALL_PASS" = true ] && echo "PASS" || echo "FAIL")
EOF

log "Results saved to $RESULTS_DIR/"
