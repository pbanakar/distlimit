# Architecture

## System Diagram

```
                         ┌──────────────────────────────────────────────┐
                         │              Docker Compose Stack            │
                         │                                              │
                         │  ┌─────────┐                                 │
              ┌──────────┼─▶│  app1   │──┐                              │
              │          │  │ (JVM 1) │  │                              │
 ┌────────┐   │          │  └─────────┘  │    ┌─────────────────────┐   │
 │ Client │───┼▶┌───────┐│  ┌─────────┐  ├───▶│       Redis         │   │
 │ (curl) │   │ │ Nginx ││─▶│  app2   │──┤    │  (shared state)    │   │
 └────────┘   │ │ :9090 ││  │ (JVM 2) │  │    │                     │   │
              │ └───────┘│  └─────────┘  │    │  Lua script runs    │   │
              │          │  ┌─────────┐  │    │  atomically here    │   │
              └──────────┼─▶│  app3   │──┘    └─────────────────────┘   │
                         │  │ (JVM 3) │                                 │
                         │  └─────────┘                                 │
                         └──────────────────────────────────────────────┘
```

## Layers

### Nginx (Load Balancer)

Nginx sits at port 9090 and round-robins incoming requests across the three
app instances. It doesn't know anything about rate limiting — it just
distributes traffic. This simulates a production load balancer (AWS ALB,
Kubernetes Ingress, etc.) that spreads requests across pods. Health checks
prevent routing to instances that aren't ready yet.

### App Instances (Spring Boot)

Each instance is an identical Spring Boot JAR running in its own JVM. They
expose `POST /api/v1/rate-limit/check?clientId=X` and delegate to
`RedisTokenBucketRateLimiter`, which executes a Lua script on Redis. The
instances hold **no rate-limiting state in memory** — all state lives in
Redis. This means you can add or remove instances without losing counts.

### Redis (State Store)

Redis stores the token count and last-refill timestamp for every client as
a hash (`HSET rate_limit:token_bucket:{clientId} tokens 8 last_refill_ms
1695000000000`). The Lua script that reads, refills, checks, and decrements
tokens runs **atomically** — Redis is single-threaded, so no other command
from any client can interleave while the script executes.

## Request Lifecycle

Here's what happens when a single request arrives:

1. **Client sends POST** to `http://localhost:9090/api/v1/rate-limit/check?clientId=alice`

2. **Nginx receives it** and picks the next upstream server via round-robin.
   Say it picks `app2:8080`. Nginx forwards the request.

3. **Spring Boot controller** (`RateLimitController.checkRateLimit()`)
   receives the request and calls `rateLimiter.tryAcquire("alice")`.

4. **RedisTokenBucketRateLimiter** builds the Redis key
   `rate_limit:token_bucket:alice`, captures the current timestamp, and
   calls `redisTemplate.execute(luaScript, key, capacity, refillRate, nowMs)`.

5. **Redis receives the EVAL command** and begins executing the Lua script
   atomically. While this script runs, no other Redis command can execute:
   - Reads `HGET rate_limit:token_bucket:alice tokens` and `last_refill_ms`
   - Computes how many tokens to refill based on elapsed time
   - Checks if `tokens >= 1`
   - If yes: decrements tokens, writes back, returns `1`
   - If no: writes updated timestamp, returns `0`

6. **Java receives the result** (`1` or `0`) and maps it to a boolean.

7. **Controller returns the response**:
   - `1` → `200 OK` with `{"allowed": true}`
   - `0` → `429 Too Many Requests` with `{"allowed": false}`

8. **Nginx forwards the response** back to the client.

Total time: ~1-5ms for the Redis call, ~10-50ms end-to-end including
Nginx routing and Spring Boot overhead.
