--[[
    Token Bucket rate limiter — atomic Lua script for Redis.

    WHY A LUA SCRIPT?
    -----------------
    Without this script, the Java client would need to:
      1. GET the current tokens and last-refill timestamp from Redis
      2. Compute the refill in Java
      3. SET the updated values back to Redis

    This GET-compute-SET sequence is NOT atomic across the network. Between
    step 1 and step 3, another service instance (or another thread) can read
    the SAME "old" token count, compute its OWN refill, and write back —
    both instances think they consumed a token, but only one decrement is
    recorded. This is the exact same check-then-act race condition that
    Phase 2 solved with synchronized blocks, except now it's across JVMs
    sharing a Redis instance, so JVM-level locks can't help.

    Redis executes each Lua script ATOMICALLY and SINGLE-THREADED: no other
    Redis command can interleave while this script is running. By moving the
    entire read-refill-check-decrement logic into this script, we get the
    same atomicity guarantee as a database transaction, but with Redis speed.

    KEYS[1] = Redis hash key for this client (e.g. "rate_limit:token_bucket:{clientId}")
    ARGV[1] = capacity (max tokens)
    ARGV[2] = refillRate (tokens per second)
    ARGV[3] = current time in milliseconds (passed from Java, not Redis TIME,
              so the clock source is consistent and testable)

    Returns: 1 if allowed, 0 if rejected
--]]

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])

-- Read current state (or nil if this client has never been seen)
local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local last_refill_ms = tonumber(redis.call('HGET', key, 'last_refill_ms'))

if tokens == nil then
    -- First request from this client — bucket starts FULL (same as in-memory impl)
    tokens = capacity
    last_refill_ms = now_ms
end

-- Refill tokens based on elapsed time
local elapsed_ms = now_ms - last_refill_ms
if elapsed_ms > 0 then
    local elapsed_seconds = elapsed_ms / 1000.0
    local new_tokens = elapsed_seconds * refill_rate
    tokens = math.min(capacity, tokens + new_tokens)
    last_refill_ms = now_ms
end

-- Try to consume one token
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', last_refill_ms)
    return 1
else
    -- Still update the refill timestamp so the next call computes elapsed
    -- time correctly (prevents "time debt" accumulation on rejected requests)
    redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', last_refill_ms)
    return 0
end
