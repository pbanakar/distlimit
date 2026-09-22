# Load Test Results

## Measured Numbers

| Test | Requests Sent | Allowed (200) | Rejected (429) | Capacity | Match? |
|------|--------------|---------------|----------------|----------|--------|
| Distributed (Nginx → 3 instances) | 50 | **10** | 40 | 10 | ✅ |
| Single instance (app1 only) | 50 | **10** | 40 | 10 | ✅ |

- **Tool:** k6 (Grafana), 50 virtual users, shared-iterations executor
- **Refill rate:** 0.001 tokens/sec (near-zero, prevents refill during test)
- **Date:** 2026-09-21

## What This Proves

The key result is not that each test individually matched the capacity. The
key result is that **both tests produced identical numbers**.

In the distributed test, 50 requests arrived at Nginx simultaneously. Nginx
round-robined them across 3 separate JVMs. Each JVM ran in its own Docker
container, its own process, its own memory space. They share nothing except
the Redis connection.

If each JVM had its own independent token count (like in Phase 2's in-memory
implementation), each would have allowed up to 10 requests independently —
for a theoretical maximum of **30 allowed** (3 × 10). Instead, we got exactly
10. This means all 3 JVMs were reading from and writing to the same atomic
counter in Redis, and the Lua script correctly serialized their access.

The single-instance control test confirms the baseline: when all 50 requests
hit one JVM, it also allows exactly 10. The distributed result matching the
single-instance result is the actual proof that distributing changed nothing
about correctness.

## What Would Have Failed

### Without Lua atomicity (naive GET-SET)

If the Java code did `GET → compute → SET` instead of `EVAL`:

```
app1: GET tokens → sees 10
app2: GET tokens → sees 10    ← read before app1's write
app3: GET tokens → sees 10    ← read before either write

app1: SET tokens=9, returns allowed
app2: SET tokens=9, returns allowed   ← overwrites app1's decrement
app3: SET tokens=9, returns allowed   ← overwrites both
```

Under 50 concurrent requests across 3 instances, the expected result with a
race condition would be **up to 30 allowed** (each instance independently
allows up to 10 from the same stale read) instead of the correct 10. The
actual number would vary between runs depending on timing — sometimes 15,
sometimes 22, sometimes 30 — which is the hallmark of a race condition.

### Without shared state (in-memory only)

If each JVM used Phase 2's in-memory `TokenBucketRateLimiter`:

- app1 allows 10, app2 allows 10, app3 allows 10
- Total allowed: **30** (3 × capacity)
- Total rejected: **20** (50 − 30)

This is deterministic, not a race condition — it's just wrong. Each JVM
correctly enforces its own limit, but the client gets 3× the intended limit
because there's no shared state.

## Performance Observations

| Test | Avg Latency | Throughput |
|------|-------------|------------|
| Distributed (via Nginx) | 257ms | 171 req/s |
| Single instance (direct) | 43ms | 681 req/s |

The distributed test is slower due to the Nginx hop and Docker networking
overhead. In production, the latency overhead of a load balancer is typically
1-5ms (not 200ms) because the load balancer and app run in the same
datacenter, not inside Docker Desktop's virtualized network on Windows.
