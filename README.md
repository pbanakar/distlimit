# DistLimit

A distributed rate limiter in Java — **Token Bucket** & **Sliding Window Log** algorithms, thread-safe, with a pluggable clock for deterministic testing.

## Status

- ✅ Phase 1: Core algorithms + unit tests
- ✅ Phase 2: Thread-safety with fine-grained per-client locking (91 tests)
- ⬜ Phase 3: Distributed state via Redis
- ⬜ Phase 4: Load testing across multiple instances
- ⬜ Phase 5: Docs and polish

## Quick Start

```bash
mvn test
mvn compile exec:java -Dexec.mainClass="com.pbanakar.ratelimiter.Playground"
```

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pluggable `java.time.Clock` | Tests run in <0.1s — no `Thread.sleep` |
| Bucket starts full | New client's first request is never rejected |
| Window boundary left-exclusive `(now−window, now]` | Avoids off-by-one ambiguity |
| `ConcurrentHashMap` + `computeIfAbsent` | Atomic per-client state creation, no TOCTOU race |
| `synchronized(bucket)` per client | Fine-grained locking — different clients never block each other |