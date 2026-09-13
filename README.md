# DistLimit

A distributed rate limiter in Java — **Token Bucket** & **Sliding Window Log** algorithms, thread-safe, with Redis-backed distributed state via atomic Lua scripting.

## Status

- ✅ Phase 1: Core algorithms + unit tests
- ✅ Phase 2: Thread-safety with fine-grained per-client locking
- ✅ Phase 3: Redis-backed distributed rate limiting via Lua script + Spring Boot REST API
- ⬜ Phase 4: Load testing across multiple instances
- ⬜ Phase 5: Docs and polish

## Quick Start

```bash
# Run unit tests only (no Docker required)
mvn test

# Run all tests including Redis integration tests (requires Docker)
mvn verify

# Start the Spring Boot server (requires Redis on localhost:6379)
mvn spring-boot:run

# Check rate limit
curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=user-1"
```

## Configuration (`application.yml`)

```yaml
rate-limiter:
  algorithm: redis-token-bucket   # token-bucket | sliding-window | redis-token-bucket
  capacity: 10
  refill-rate: 1.0
```

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pluggable `java.time.Clock` | Tests run in <0.1s — no `Thread.sleep` |
| `ConcurrentHashMap` + `computeIfAbsent` | Atomic per-client state creation, no TOCTOU race |
| `synchronized(bucket)` per client | Fine-grained locking — different clients never block each other |
| Redis Lua script for distributed ops | `EVAL` runs atomically and single-threaded — no cross-process race condition |
| Testcontainers (not mocks) | Proves Lua atomicity against a real Redis instance |
| Spring Data Redis + Lettuce | Lettuce is thread-safe by default (Netty-based), no connection pool needed |