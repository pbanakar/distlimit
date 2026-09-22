# Algorithms

## Comparison

| | Token Bucket | Sliding Window Log |
|---|---|---|
| **Allows bursts** | Yes — idle users can spend saved-up tokens instantly | No — strictly enforces the limit at all times |
| **Memory per client** | 2 numbers (token count + last refill time) | 1 list of timestamps (grows with traffic) |
| **Precision** | Approximate — a burst followed by steady traffic can briefly exceed the "average" rate | Exact — never allows more than N requests in any window of size W |
| **Best use case** | General API rate limiting where occasional bursts are acceptable (most real-world APIs) | Login attempt limiting, fraud prevention, or anywhere bursts are dangerous |

## Token Bucket

Imagine a bucket that holds coins. The bucket has a maximum capacity — say 10
coins. Every second, one new coin drops in (the refill rate). When a request
arrives, it tries to take a coin. If the bucket has one, the request is allowed
and a coin is removed. If the bucket is empty, the request is rejected.

The key behavior is **bursting**. If a user has been quiet for 10 seconds, the
bucket fills up to capacity (10 coins). Their next 10 requests go through
instantly because the coins are already there. After that, they're limited to
the refill rate: 1 request per second.

This is how most production APIs work — Stripe, GitHub, and AWS all use token
bucket or a close variant. Bursting is desirable because real traffic is bursty:
a user loads a page and their browser fires 8 API calls at once. You want that
to work without hitting rate limits.

### What the code stores per client

Two numbers in a `TokenBucket` object (or Redis hash):
- `tokens`: current token count (a `double` to handle fractional refills)
- `lastRefillTime`: timestamp of the last refill calculation

### What happens on each request

1. Calculate how much time has elapsed since `lastRefillTime`
2. Refill: `tokens = min(capacity, tokens + elapsed * refillRate)`
3. If `tokens >= 1`: decrement and return `true` (allowed)
4. Otherwise: return `false` (rejected)

### The "bucket starts full" decision

When a client is seen for the first time, the bucket starts at capacity (full).
The alternative — starting empty — would mean every new client's first request
is rejected, which is a terrible user experience. A full starting bucket also
matches the intuition: "you're allowed 10 requests, starting now."

## Sliding Window Log

Instead of tokens, this algorithm keeps a **log of every request timestamp**
within the current time window. When a request arrives, it:

1. Removes all timestamps older than `now - windowSize` (cleanup)
2. Counts the remaining timestamps
3. If the count is below `maxRequests`: records `now` and returns `true`
4. Otherwise: returns `false`

The precision comes from never approximating. At any instant, the algorithm
knows the exact number of requests within the window. If `maxRequests` is 5
and the window is 1 minute, you can never have 6 requests within any
60-second period — no matter when they arrive.

The cost is memory. Under high traffic, the timestamp list for a single client
can hold `maxRequests` entries. For Token Bucket, it's always just 2 numbers.

### When to choose Sliding Window over Token Bucket

Use Sliding Window when bursts are a security risk. Login rate limiting is the
classic example: "no more than 5 login attempts per minute" should mean
*exactly* 5, not "5 on average with occasional bursts of 10."

## Clock Injection

Both algorithms accept an optional `java.time.Clock` parameter:

```java
// Production: uses real system time
new TokenBucketRateLimiter(10, 1.0);

// Tests: uses a controllable fake clock
Clock fakeClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
new TokenBucketRateLimiter(10, 1.0, fakeClock);
```

### Why it exists

Rate limiting is inherently time-dependent. Without clock injection, testing
"tokens refill after 5 seconds" requires `Thread.sleep(5000)` — making each
test take 5 real seconds and making the test flaky (what if the OS scheduler
delays the thread?).

With a fake clock, tests advance time instantly:
```java
clock = Clock.offset(clock, Duration.ofSeconds(5)); // "5 seconds pass"
```

All 91 unit tests run in under 1 second because no test ever sleeps.

### What would break without it

- Tests would take minutes instead of seconds
- Tests would be flaky (timing-dependent)
- You couldn't test edge cases like "exactly at the window boundary"
- You couldn't test the Redis Lua script with deterministic timestamps
