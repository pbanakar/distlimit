# DistLimit — The Complete Guide

> A distributed rate limiter built from scratch in Java, designed so you can
> explain every line in a technical interview.

---

## What is rate limiting and why should you care?

Imagine you run a weather API. A free-tier user should get 10 requests per
minute, a paid user gets 1000. Without rate limiting:

- One buggy client can send 100,000 requests/second and crash your server
- A malicious user can brute-force your login endpoint
- Your AWS bill explodes because there's no traffic control

**Rate limiting** = a bouncer at the door. Each client gets a budget. When
they've used it up, they get a `429 Too Many Requests` until their budget
resets.

This project implements that bouncer — from a simple in-memory Java class all
the way to a distributed Redis-backed service that works across multiple
servers.

---

## How this project is structured

```
distlimit/
├── src/main/java/com/pbanakar/ratelimiter/
│   ├── core/
│   │   ├── RateLimiter.java                  ← The interface (1 method)
│   │   ├── TokenBucketRateLimiter.java       ← Algorithm 1 (in-memory)
│   │   ├── SlidingWindowRateLimiter.java     ← Algorithm 2 (in-memory)
│   │   └── RedisTokenBucketRateLimiter.java  ← Algorithm 1 (Redis-backed)
│   ├── api/
│   │   └── RateLimitController.java          ← REST endpoint
│   ├── config/
│   │   └── RateLimiterConfig.java            ← Reads application.yml
│   ├── DistLimitApplication.java             ← Spring Boot main class
│   └── Playground.java                       ← Run this to experiment!
├── src/main/resources/
│   ├── scripts/token_bucket.lua              ← Lua script (runs inside Redis)
│   └── application.yml                       ← Configuration
└── src/test/java/                            ← 135 tests
```

---

## The two algorithms, explained simply

### Algorithm 1: Token Bucket

Think of a bucket that holds coins (tokens):

```
     ┌─────────┐
     │ 🪙🪙🪙  │  ← bucket starts full (capacity = 3)
     │         │
     └─────────┘
         ↑
    1 coin added every second (refill rate = 1/sec)
```

**Every request takes 1 coin.** If the bucket is empty → request rejected.
Coins are added back at a steady rate, but the bucket never overflows past
`capacity`.

**Why it's good:** Allows bursts. If a user has been idle, their bucket is
full and they can send `capacity` requests instantly. This is how most real
APIs work (AWS, Stripe, GitHub).

**Try it:**
```bash
mvn compile exec:java -Dexec.mainClass="com.pbanakar.ratelimiter.Playground"
```

You'll see:
```
── Token Bucket (capacity=3, refill=1 token/sec) ──

Sending 5 requests instantly (bucket holds 3):
  Request 1: ✅ ALLOWED     ← coin 1 used
  Request 2: ✅ ALLOWED     ← coin 2 used
  Request 3: ✅ ALLOWED     ← coin 3 used (bucket empty!)
  Request 4: ❌ REJECTED    ← no coins left
  Request 5: ❌ REJECTED

⏳ Sleeping 2 seconds to let tokens refill...

Sending 3 more requests after 2s refill (~2 tokens back):
  Request 6: ✅ ALLOWED     ← 2 coins refilled in 2 seconds
  Request 7: ✅ ALLOWED
  Request 8: ❌ REJECTED    ← only 2 refilled, not 3
```

### Algorithm 2: Sliding Window Log

Instead of coins, this one remembers **every request timestamp** in the last
N seconds:

```
Timeline:  ──────────────[────── 2 sec window ──────]──→ now
                          │  req1  req2  req3        │
                          │  (3 in window = at limit)│
                          └──────────────────────────┘
```

**If there are already `maxRequests` timestamps in the current window → rejected.**
Old timestamps that fall outside the window are cleaned up automatically.

**Why it's different from Token Bucket:**
- No bursts allowed — the limit is strictly enforced
- Uses more memory (stores every timestamp vs. just 2 numbers)
- More precise at boundaries

---

## Phase 1: The basics (what you have now)

Both algorithms implement one interface:

```java
public interface RateLimiter {
    boolean tryAcquire(String clientId);  // true = allowed, false = rejected
}
```

That's it. One method. Each client (identified by `clientId`) gets their own
independent bucket/window. Alice's traffic never affects Bob.

### The Clock trick (important for interviews!)

Real code uses `System.currentTimeMillis()`. But tests that call
`Thread.sleep(2000)` are slow and flaky. So we inject a **pluggable clock**:

```java
// Production: uses real time
new TokenBucketRateLimiter(10, 1.0);

// Tests: uses a fake clock we control
Clock fakeClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
new TokenBucketRateLimiter(10, 1.0, fakeClock);
// Now we can "advance time" without sleeping!
```

This means all 91 unit tests run in **< 1 second** with zero `Thread.sleep`.

---

## Phase 2: Thread safety

### The problem

Two threads call `tryAcquire("alice")` at the exact same time:

```
Thread 1: reads tokens=1  →  "ok, 1 >= 1"  →  sets tokens=0  →  returns true ✅
Thread 2: reads tokens=1  →  "ok, 1 >= 1"  →  sets tokens=0  →  returns true ✅
                ↑
            BOTH read the same old value before either writes!
            Alice got 2 requests through, but should have gotten 1.
```

This is a **check-then-act race condition** (aka TOCTOU — Time of Check,
Time of Use).

### The fix: two levels of protection

**Level 1 — Map level:** `ConcurrentHashMap` + `computeIfAbsent`
```java
// BAD: two threads can both see "absent" and create separate buckets
if (!map.containsKey(id)) map.put(id, new Bucket());  // TOCTOU race!

// GOOD: exactly one Bucket created, all threads share it
Bucket b = map.computeIfAbsent(id, k -> new Bucket());  // atomic
```

**Level 2 — Bucket level:** `synchronized` on the per-client object
```java
synchronized (bucket) {           // ← only blocks threads for the SAME client
    refillTokens(bucket);
    if (bucket.tokens >= 1) {
        bucket.tokens--;
        return true;
    }
    return false;
}
```

**Key insight:** the lock is on `bucket` (per-client), not on the whole map.
So Alice's traffic never blocks Bob. This is **fine-grained locking**.

### Try it in the Playground:

The concurrency demo fires 20 threads at the same bucket (capacity=5):

```
── Concurrency Demo (Token Bucket, capacity=5, 20 threads) ──

All 20 threads ready. Firing simultaneously...

  Thread  3: ✅ ALLOWED
  Thread  7: ❌ REJECTED
  Thread  1: ✅ ALLOWED
  ...
  Summary: 5 allowed, 15 rejected (capacity was 5)
  ✅ CORRECT
```

If the locking were broken, you'd see more than 5 allowed.

---

## Phase 3: Going distributed with Redis

### The problem Phase 3 solves

Phase 2's locking works within **one JVM**. But in production you have
**multiple servers** behind a load balancer:

```
                    ┌─── Server A (JVM 1): tokens=5 in memory
User → Load      ──┤
       Balancer    └─── Server B (JVM 2): tokens=5 in memory
                                          ↑
                           Each server has its OWN map!
                           User gets 5 + 5 = 10 requests instead of 5.
```

**Fix:** Move the token state to a **shared Redis** instance that all servers
read from.

### But... Redis has the same race condition!

A naive approach:

```java
double tokens = redis.GET("user:tokens");    // Step 1: READ from Redis
if (tokens >= 1) {
    redis.SET("user:tokens", tokens - 1);    // Step 2: WRITE to Redis
    return true;
}
```

Between Steps 1 and 2, Server B can read the **same old value**. It's the
exact same race condition as Phase 2, but across the network!

### The fix: Redis Lua scripting

Redis can run **Lua scripts atomically**. While a Lua script is executing,
**no other Redis command can run** — not from any client, any server, anywhere.

```lua
-- This entire script runs as ONE atomic operation inside Redis:
local tokens = redis.call('HGET', key, 'tokens')     -- read
tokens = refill(tokens, elapsed_time)                  -- compute
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HSET', key, 'tokens', tokens)          -- write
    return 1   -- allowed
end
return 0       -- rejected
```

The Java code just calls `EVAL` with this script. Redis does the rest.

### Try it yourself:

**Step 1:** Make sure Docker is running (for Redis)

**Step 2:** Start the Spring Boot server
```bash
# Start Redis in Docker
docker run -d --name redis-local -p 6379:6379 redis:7-alpine

# Start the app
mvn spring-boot:run
```

**Step 3:** Hit the endpoint with curl
```bash
# First few requests — should return 200 with {"allowed":true}
curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=user-1"
# {"allowed":true}

curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=user-1"
# {"allowed":true}

# ... keep going until you exhaust the limit (default: 10 tokens)

# After 10 requests — returns 429 with {"allowed":false}
curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=user-1"
# {"allowed":false}
```

**Step 4:** Try a different client (independent bucket!)
```bash
curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=user-2"
# {"allowed":true}   ← user-2 has their own bucket, unaffected by user-1
```

**Step 5:** Wait for refill
```bash
# Wait 5 seconds (refill rate = 1 token/sec, so ~5 tokens refill)
# Then try user-1 again — should be allowed!
curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=user-1"
# {"allowed":true}
```

**Step 6:** Cleanup
```bash
docker stop redis-local && docker rm redis-local
```

---

## Configuration

All settings are in `src/main/resources/application.yml`:

```yaml
rate-limiter:
  algorithm: redis-token-bucket   # Options:
                                  #   token-bucket        → in-memory (Phase 1/2)
                                  #   sliding-window      → in-memory (Phase 1/2)
                                  #   redis-token-bucket  → Redis-backed (Phase 3)
  capacity: 10                    # Max tokens per client
  refill-rate: 1.0                # Tokens added per second
  window-size-millis: 60000       # Sliding window only
```

**To experiment:** change `capacity` to `3` and `refill-rate` to `0.5`, restart,
and you'll see limits hit much faster.

---

## Running tests

```bash
# Unit tests only (fast, no Docker needed) — 91 tests
mvn test

# All tests including Redis integration tests (needs Docker) — 135 tests
mvn verify
```

### What the tests prove:

| Test | What it proves |
|------|---------------|
| Single-client exhaustion | Exactly `capacity` requests allowed, then rejected |
| Token refill after time | Tokens actually regenerate correctly |
| Multi-client isolation | Alice's limit doesn't affect Bob |
| Concurrent threads (Phase 2) | 20 threads, 1 bucket → exactly `capacity` allowed |
| Concurrent threads on Redis (Phase 3) | 50 threads hitting Redis simultaneously → exactly `capacity` allowed |
| REST endpoint 200/429 | Correct HTTP status codes and JSON body |

---

## How to explain this in an interview

### "Walk me through your rate limiter project"

> "I built a distributed rate limiter in 5 phases. I started with two
> algorithms — Token Bucket and Sliding Window — as plain Java classes behind
> a shared interface. I used a pluggable Clock so tests run in milliseconds
> without Thread.sleep.
>
> Then I made them thread-safe using ConcurrentHashMap with computeIfAbsent
> for atomic initialization, plus fine-grained synchronized blocks on per-client
> state — so different clients never contend for the same lock.
>
> For the distributed version, I moved state to Redis. But a naive GET-then-SET
> has the same race condition across the network. So I used a Redis Lua script
> that runs the entire read-refill-check-decrement sequence atomically inside
> Redis. I proved it with 50 concurrent threads hitting the same Redis instance."

### "Why Token Bucket over other algorithms?"

> "Token Bucket allows bursts — a user who's been idle for a while has a full
> bucket and can send several requests instantly. Most real APIs want this
> behavior. Sliding Window is stricter — no bursts — which is better for things
> like login rate limiting where you truly want 'no more than 5 attempts per
> minute, ever'."

### "Why Lua scripting instead of Redis transactions (MULTI/EXEC)?"

> "MULTI/EXEC is optimistic — it watches keys and retries on conflict. Under
> high contention, you get lots of retries. Lua scripts are pessimistic — Redis
> runs them single-threaded, so there's zero contention. For rate limiting,
> where every request hits the same key, Lua is the right choice."

### "Why not just use Guava's RateLimiter?"

> "Guava's RateLimiter is single-JVM only. It can't work across multiple
> service instances. Also, building it from scratch means I understand every
> tradeoff — burst vs. strict, memory vs. precision, in-process vs. distributed
> atomicity."

---

## Cheat sheet: commands you'll use most

```bash
# Run the Playground (no Docker needed)
mvn compile exec:java -Dexec.mainClass="com.pbanakar.ratelimiter.Playground"

# Run unit tests
mvn test

# Run all tests (needs Docker)
mvn verify

# Start Redis + Spring Boot
docker run -d --name redis-local -p 6379:6379 redis:7-alpine
mvn spring-boot:run

# Hit the endpoint
curl -X POST "http://localhost:8080/api/v1/rate-limit/check?clientId=alice"

# Rapid-fire 15 requests to see the limit
for i in $(seq 1 15); do
  echo "Request $i: $(curl -s -o /dev/null -w '%{http_code}' -X POST \
    'http://localhost:8080/api/v1/rate-limit/check?clientId=alice')"
done

# Stop Redis
docker stop redis-local && docker rm redis-local
```

---

## Phase 4: Proving it works across multiple instances

### The claim to prove

Phase 3 *implemented* distributed rate limiting. Phase 4 *proves* it works by
running **3 separate JVMs** behind an **Nginx load balancer**, sharing **one
Redis**, and firing 50 concurrent requests. If the Lua script is truly atomic,
exactly 10 should be allowed (capacity=10) regardless of which instance
handles each request.

### The stack

```
                    ┌─── app1 (JVM 1) ───┐
User → Nginx ──────┤─── app2 (JVM 2) ───┼──→ Redis (shared state)
  (port 9090)       └─── app3 (JVM 3) ───┘
```

All defined in `docker-compose.yml`. Nginx round-robins requests. Each app
instance connects to the same Redis. Health checks ensure Nginx only routes
to instances that are ready.

### Running the load test yourself

```bash
# Start everything
docker compose up -d --build

# Wait for all containers to be healthy
docker compose ps

# Flush Redis
docker compose exec redis redis-cli FLUSHALL

# Distributed test: 50 requests → Nginx → 3 instances
docker run --rm --network distlimit_default \
  -v "${PWD}/loadtest:/scripts" \
  -e TARGET_URL=http://nginx:80 \
  -e CLIENT_ID=dist-client \
  grafana/k6:latest run /scripts/load-test.js

# Flush Redis again
docker compose exec redis redis-cli FLUSHALL

# Control test: 50 requests → app1 only
docker run --rm --network distlimit_default \
  -v "${PWD}/loadtest:/scripts" \
  -e TARGET_URL=http://app1:8080 \
  -e CLIENT_ID=single-client \
  grafana/k6:latest run /scripts/load-test.js

# Tear down
docker compose down
```

### Measured results

| Test | Sent | Allowed | Rejected | Correct? |
|------|------|---------|----------|----------|
| Distributed (3 instances) | 50 | 10 | 40 | ✅ |
| Single instance | 50 | 10 | 40 | ✅ |

Both identical. Distributing across 3 JVMs changed nothing about correctness.
The Redis Lua script is the reason — it's the single point of atomic
truth, no matter which JVM calls it.
