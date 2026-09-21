# DistLimit

A distributed rate limiter in Java — **Token Bucket** & **Sliding Window Log** algorithms, thread-safe, with Redis-backed distributed state via atomic Lua scripting.

## Status

- ✅ Phase 1: Core algorithms + unit tests
- ✅ Phase 2: Thread-safety with fine-grained per-client locking
- ✅ Phase 3: Redis-backed distributed rate limiting via Lua script + Spring Boot REST API
- ✅ Phase 4: Dockerized multi-instance deployment + load test proof
- ⬜ Phase 5: Docs and polish

## Quick Start

```bash
# Run the playground (no Docker needed)
mvn compile exec:java -Dexec.mainClass="com.pbanakar.ratelimiter.Playground"

# Run unit tests (no Docker needed)
mvn test

# Run all tests including Redis integration tests (needs Docker)
mvn verify

# Start the full distributed stack (Redis + 3 app instances + Nginx)
docker compose up -d --build

# Hit the rate limit endpoint
curl -X POST "http://localhost:9090/api/v1/rate-limit/check?clientId=user-1"

# Tear down
docker compose down
```

## Load Test Results (Phase 4)

Fired 50 concurrent requests for the same `clientId` with `capacity=10`:

| Test | Sent | Allowed (200) | Rejected (429) | Match? |
|------|------|---------------|----------------|--------|
| **Distributed** (Nginx → 3 instances) | 50 | 10 | 40 | ✅ |
| **Single instance** (app1 only) | 50 | 10 | 40 | ✅ |

Both tests produced identical results — distributing across 3 JVMs sharing one Redis changed nothing about correctness. See [`results/summary.txt`](results/summary.txt) for full details.

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pluggable `java.time.Clock` | Tests run in <0.1s — no `Thread.sleep` |
| `ConcurrentHashMap` + `computeIfAbsent` | Atomic per-client state creation, no TOCTOU race |
| `synchronized(bucket)` per client | Fine-grained locking — different clients never block each other |
| Redis Lua script for distributed ops | `EVAL` runs atomically and single-threaded — no cross-process race |
| Testcontainers (not mocks) | Proves Lua atomicity against a real Redis instance |
| Lettuce over Jedis | Thread-safe by default (Netty-based), no connection pool needed |
| k6 over JMeter | Plain JS scripts, single binary, CI-friendly |
| Multi-stage Dockerfile | ~200MB runtime image vs ~800MB build image |
| Non-root container user | Limits blast radius if the app is compromised |