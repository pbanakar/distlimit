# Redis Atomicity

This document explains why distributed rate limiting is hard, why the naive
approach fails, and how Redis Lua scripting solves it.

## Why GET-then-SET From Java Is Unsafe

Consider two service instances (app1 and app2) sharing Redis, both processing
a request for the same client at the same time:

```
Timeline:
─────────────────────────────────────────────────────────────────

app1:  GET tokens ──────────────────── SET tokens=9 ── return OK
       (reads 10)                      (decrements)

app2:         GET tokens ──── SET tokens=9 ── return OK
              (reads 10)      (decrements)
              ↑
              STALE READ: app1 hasn't written yet
```

Both instances read `tokens=10`, both compute `10 - 1 = 9`, both write `9`.
The client used two tokens but only one decrement was recorded. This is a
**check-then-act race condition** — the same bug that `synchronized` fixes
within a single JVM, but across the network where JVM locks can't help.

The window for this race is small (milliseconds) but under concurrent load,
it happens reliably. Our load test fires 50 requests simultaneously — without
atomicity, the race triggers every time.

## Why Lua Scripting Fixes It

Redis is single-threaded. At any given moment, it processes exactly one
command. When a Lua script runs via `EVAL`, Redis:

1. Pauses all other client connections
2. Executes the entire script to completion
3. Resumes normal command processing

No other `GET`, `SET`, `EVAL`, or any command from any client can interleave.
The script sees a consistent snapshot of the data and its writes are the only
writes that happen during execution.

This gives us the same guarantee as a database transaction with
`SERIALIZABLE` isolation — but without the overhead of locks, WAL, or
rollback machinery. Redis Lua is essentially a stored procedure that runs
in a single-threaded, in-memory database.

## The Lua Script, Line by Line

```lua
-- KEYS[1] = Redis hash key for this client
-- ARGV[1] = capacity (max tokens)
-- ARGV[2] = refillRate (tokens per second)
-- ARGV[3] = current time in milliseconds

local key = KEYS[1]
local capacity = tonumber(ARGV[1])        -- e.g. 10
local refill_rate = tonumber(ARGV[2])     -- e.g. 1.0
local now_ms = tonumber(ARGV[3])          -- e.g. 1695000005000

-- Read current state from the hash
local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local last_refill_ms = tonumber(redis.call('HGET', key, 'last_refill_ms'))

if tokens == nil then
    -- First request: bucket starts full (design decision: not empty)
    tokens = capacity
    last_refill_ms = now_ms
end

-- Refill based on elapsed time
local elapsed_ms = now_ms - last_refill_ms
if elapsed_ms > 0 then
    local elapsed_seconds = elapsed_ms / 1000.0
    local new_tokens = elapsed_seconds * refill_rate
    tokens = math.min(capacity, tokens + new_tokens)  -- cap at capacity
    last_refill_ms = now_ms
end

-- Try to consume one token
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', last_refill_ms)
    return 1   -- allowed
else
    redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', last_refill_ms)
    return 0   -- rejected
end
```

Why write on rejection too? So the next request calculates elapsed time from
the correct base. Without this, rejected requests create "time debt" — the
next allowed request would retroactively refill all the time spent being
rejected.

## EVAL vs EVALSHA

**EVAL** sends the full script text with every call. Redis parses and compiles
it each time.

**EVALSHA** sends only the SHA1 hash of the script. Redis looks up the compiled
version in its script cache. If found, it skips parsing. If not found (cache
miss after restart), it returns `NOSCRIPT` and the client must fall back to
`EVAL`.

**What we use:** `EVAL` via Spring's `DefaultRedisScript`. Spring Data Redis
automatically handles the EVAL/EVALSHA optimization: it tries `EVALSHA` first
and falls back to `EVAL` on `NOSCRIPT`. We get the performance benefit without
manual cache management.

**Tradeoff:** `EVAL` is simpler and always works. `EVALSHA` saves ~2KB of
network bandwidth per call (the script text). For a 68-line script at
thousands of requests per second, the bandwidth saving is real but not
critical. The latency difference is negligible because Redis parses Lua
in microseconds.

## What Happens If Redis Restarts

Redis's Lua script cache is **in-memory only**. When Redis restarts:

1. All script caches are cleared
2. All rate-limiting state (token counts) is lost (unless Redis persistence
   is enabled with RDB/AOF)
3. The next `EVALSHA` call gets `NOSCRIPT`, and Spring automatically falls
   back to `EVAL`, which re-caches the script

For rate limiting, losing state on restart is acceptable — all buckets reset
to full, which means clients get a temporary "free" window. This is far
better than the alternative of blocking all requests until state is
reconstructed.

If you need state to survive restarts, enable Redis persistence:
- **RDB**: periodic snapshots (lose data since last snapshot)
- **AOF**: append-only log (lose at most 1 second of data with `appendfsync everysec`)

For rate limiting, neither is typically configured because the data is
ephemeral and regenerates naturally through the refill mechanism.
