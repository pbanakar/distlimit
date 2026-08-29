# DistLimit

A distributed rate limiter built in Java, implementing **Token Bucket** and
**Sliding Window Log** algorithms with a pluggable clock for deterministic testing.

## Status

- ✅ Phase 1: Core algorithms + unit tests (11 passing)
- ⬜ Phase 2: Thread-safety for concurrent access
- ⬜ Phase 3: Distributed state via Redis
- ⬜ Phase 4: Load testing across multiple instances
- ⬜ Phase 5: Docs and polish

## Quick Start

```bash
# Run all tests
mvn test

# Run the interactive playground
mvn compile exec:java -Dexec.mainClass="com.pbanakar.ratelimiter.Playground"
```

## Algorithms

### Token Bucket

Each client gets a bucket that holds up to `capacity` tokens. Tokens refill at
a steady `refillRate` (tokens/sec). Each request consumes 1 token. If the bucket
is empty, the request is rejected.

**Trade-offs:** Allows bursts up to `capacity` (good for APIs), memory-efficient
(2 numbers per client), but not precise at boundaries.

### Sliding Window Log

Each client's request timestamps are stored in a deque. On every request, expired
timestamps (older than `windowSizeMillis`) are evicted, and the request is allowed
only if the remaining count is below `maxRequests`.

**Trade-offs:** Precise (no approximation), but higher memory per client
(stores up to `maxRequests` timestamps). No burst allowance — the limit is
strictly enforced over the most recent window.

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pluggable `java.time.Clock` | Tests run in <0.1s with no `Thread.sleep` — a real production pattern |
| Bucket starts full | Avoids the common bug where a new client's first request is rejected |
| Window boundary is left-exclusive `(now−window, now]` | A timestamp exactly at the boundary is expired — avoids off-by-one ambiguity |
| Per-client state via `Map` | Multi-tenant isolation — one client can't exhaust another's quota |