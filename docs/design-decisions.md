# Design Decisions

Every choice in this project was made deliberately. Each section explains
what was chosen, what the alternative was, and what the tradeoff is.

---

## ConcurrentHashMap + computeIfAbsent

**Chose:** `ConcurrentHashMap` with `computeIfAbsent` for per-client state storage.

**Alternative:** Plain `HashMap` with `synchronized` on the whole map, or
`Collections.synchronizedMap`.

**Why this one:** `computeIfAbsent` is atomic — it checks if a key exists and
creates the value in a single operation, eliminating the TOCTOU (time-of-check,
time-of-use) race where two threads both see "key absent" and both create
separate bucket objects. A synchronized map would work but would serialize
ALL operations on the entire map, even for unrelated clients.

**Tradeoff:** `ConcurrentHashMap` uses more memory per segment than a plain
`HashMap`, but the lock contention reduction is worth it for any non-trivial
number of clients.

---

## Fine-grained per-client locking vs coarse lock

**Chose:** `synchronized(bucket)` on the per-client bucket object — each client
has its own lock.

**Alternative:** One global `synchronized` block or `ReentrantLock` protecting
all bucket operations.

**Why this one:** A coarse lock means Alice's request blocks Bob's request, even
though they have completely independent buckets. With per-client locking,
Alice and Bob never contend. Only threads accessing the *same* client's bucket
are serialized.

**Tradeoff:** More lock objects in memory (one per client). For millions of
clients, this could matter — but at that scale, you'd be using Redis anyway
(Phase 3), not in-memory locks.

---

## Lua script vs GET-SET from Java

**Chose:** A single Lua script executed via Redis `EVAL` that handles the entire
read-refill-check-decrement cycle atomically.

**Alternative 1:** GET-compute-SET from Java — read the token count from Redis,
do the refill math in Java, write the result back.

**Alternative 2:** Redis `MULTI/EXEC` transactions with `WATCH` on the key
(optimistic locking).

**Why this one:**
- GET-SET is fundamentally unsafe: between the GET and SET, another replica can
  read the same stale value. This is the cross-process version of the same race
  condition Phase 2 fixed within a single JVM.
- `MULTI/EXEC` with `WATCH` is optimistic: if the key changes between `WATCH`
  and `EXEC`, the transaction aborts and must be retried. Under high contention
  (every request touches the same key), retries pile up.
- Lua is pessimistic: Redis runs it single-threaded, so there's zero contention
  and zero retries. For rate limiting — where *every* request hits the same
  per-client key — this is the right model.

**Tradeoff:** Lua scripts block Redis while executing. A slow script delays all
other commands. Our script is ~15 operations and runs in microseconds, so this
is not a concern. For complex multi-key operations, Lua blocking could matter.

---

## Lettuce vs Jedis

**Chose:** Lettuce (via `spring-boot-starter-data-redis`).

**Alternative:** Jedis.

**Why this one:** Lettuce uses a single, thread-safe connection (Netty-based,
non-blocking I/O). Jedis uses blocking I/O and requires a connection pool
(`JedisPool`) to be thread-safe. Lettuce is also the Spring Boot default,
meaning zero extra configuration.

**Tradeoff:** Jedis is simpler to debug (blocking = straightforward stack
traces). Lettuce's async/reactive model can make stack traces harder to read.
For this project's synchronous use case, both would work equally well.

---

## Testcontainers vs mocks

**Chose:** Testcontainers — spins up a real Redis 7 Docker container for
integration tests.

**Alternative:** Mock `StringRedisTemplate` and verify method calls.

**Why this one:** The entire point of Phase 3 is proving the Lua script runs
atomically on a real Redis server. Mocking Redis would test Java logic only —
exactly what Phase 2 already proved. You can't verify Lua script correctness
without executing it on actual Redis.

**Tradeoff:** Tests require Docker running, take longer to start (~2-3s for
container spin-up), and can't run in environments without Docker. Unit tests
(`mvn test`) still run without Docker; only integration tests (`mvn verify`)
need it.

---

## Multi-stage Dockerfile

**Chose:** Two-stage build — Maven + JDK for compilation, then slim JRE Alpine
for runtime.

**Alternative:** Single-stage image with JDK, Maven, and source code all
included.

**Why this one:** The runtime image is ~200MB instead of ~800MB. It contains
only the JRE and the fat JAR — no compiler, no Maven, no source code. Smaller
images deploy faster, have a smaller attack surface, and use less disk.

**Tradeoff:** Build is slightly more complex (two FROM statements). Dependency
downloads are cached in a separate layer (`COPY pom.xml` + `mvn dependency:go-offline`
before copying source), so rebuilds after code changes skip the download step.

---

## Non-root container user

**Chose:** Create a dedicated `appuser` and run the JAR as that user.

**Alternative:** Run as root (the Docker default).

**Why this one:** If an attacker exploits a vulnerability in the Spring Boot app,
running as root gives them full control of the container — and potentially the
host via container escape exploits. Running as a low-privilege user limits the
blast radius: the attacker can't modify system files, install packages, or
access other containers' network interfaces.

**Tradeoff:** None in practice. The app only needs to read its own JAR and write
to stdout. No file writes, no port binding below 1024. Non-root is free.

---

## Fail-open vs fail-closed

**Chose:** Fail-open by default — if Redis is unreachable, allow the request
and log a warning.

**Alternative:** Fail-closed — if Redis is unreachable, reject the request
(return 500 or 429).

**Why this one:** A rate limiter is a protective layer, not a core business
function. If the rate limiter is down, the API itself is still healthy. Failing
closed means a Redis outage cascades into a full API outage — every single
request returns 500. Failing open means you temporarily lose rate limiting
(bad) but the API stays available (critical).

**Tradeoff:** During a Redis outage, there's no rate limiting at all. A
malicious client could exploit this window. In practice, Redis outages are
rare and short, and the alternative (complete API unavailability) is almost
always worse. This is configurable: set `rate-limiter.fail-open: false` for
environments where rate enforcement is more important than availability.
