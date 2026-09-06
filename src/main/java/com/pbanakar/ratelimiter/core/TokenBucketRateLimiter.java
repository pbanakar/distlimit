package com.pbanakar.ratelimiter.core;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rate limiter that uses the <strong>Token Bucket</strong> algorithm.
 *
 * <h3>How it works</h3>
 * <p>Each client is assigned a virtual "bucket" that holds up to {@code capacity}
 * tokens. Tokens are added at a steady {@code refillRate} (tokens per second),
 * but the bucket never holds more than {@code capacity} tokens.
 * Every call to {@link #tryAcquire(String)} first refills the bucket based on
 * how much real time has elapsed since the last refill, then tries to remove
 * one token. If a token is available the request is allowed; otherwise it is
 * rejected.</p>
 *
 * <h3>Trade-offs</h3>
 * <ul>
 *   <li><strong>Burst-friendly</strong> — a client that has been idle accumulates
 *       tokens (up to {@code capacity}), so it can send a burst of requests
 *       immediately. This is often desirable for APIs that want to tolerate
 *       short spikes.</li>
 *   <li><strong>Memory-efficient</strong> — each client requires only a small
 *       fixed-size record (current token count + last-refill timestamp), unlike
 *       sliding-window-log which stores every request timestamp.</li>
 *   <li><strong>Not precise at boundaries</strong> — because refill is computed
 *       lazily, the actual enforcement granularity depends on how frequently
 *       {@code tryAcquire} is called.</li>
 * </ul>
 *
 * <h3>Thread safety — locking strategy (Phase 2)</h3>
 * <p>This implementation uses <strong>fine-grained, per-client locking</strong>:</p>
 * <ol>
 *   <li>The client → Bucket map is a {@link ConcurrentHashMap}. New entries are
 *       created atomically via {@code computeIfAbsent}, which guarantees that
 *       even if two threads call {@code tryAcquire} for a brand-new clientId at
 *       the exact same instant, exactly one Bucket is created and shared.
 *       A naive {@code if (!map.containsKey(id)) map.put(id, new Bucket(...))}
 *       has a TOCTOU race: both threads could see "absent", both create a Bucket,
 *       and one would silently overwrite the other, losing token state.</li>
 *   <li>The refill + consume logic inside {@code tryAcquire} is a classic
 *       check-then-act sequence (read tokens → refill → decrement). We protect
 *       it by synchronizing on the individual {@code Bucket} object, so two
 *       threads hitting <em>different</em> clients never contend for the same
 *       lock. This is critical for multi-tenant systems: one client's high
 *       traffic must not serialise another client's requests.</li>
 * </ol>
 *
 * @see RateLimiter
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final double refillRate; // tokens per second
    private final Clock clock;

    // ConcurrentHashMap ensures thread-safe reads/writes to the map itself.
    // Individual Bucket mutations are further protected by synchronized(bucket).
    private final ConcurrentMap<String, Bucket> buckets;

    /**
     * Internal per-client bucket state.
     * <p>Access to mutable fields ({@code tokens}, {@code lastRefillNanos})
     * must be guarded by {@code synchronized(this)}.
     */
    private static class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    /**
     * Creates a new Token Bucket rate limiter.
     *
     * @param capacity   maximum number of tokens a bucket can hold (also the
     *                   initial number of tokens for new clients)
     * @param refillRate tokens added per second (e.g. 10.0 means ten tokens/sec)
     * @param clock      time source — inject a fixed or manually-advancing clock
     *                   in tests for deterministic behaviour
     * @throws IllegalArgumentException if capacity &lt; 1 or refillRate &le; 0
     */
    public TokenBucketRateLimiter(int capacity, double refillRate, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0, got " + refillRate);
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.clock = clock;
        this.buckets = new ConcurrentHashMap<>();
    }

    /**
     * Convenience constructor that uses the system UTC clock.
     *
     * @param capacity   maximum tokens per bucket
     * @param refillRate tokens added per second
     */
    public TokenBucketRateLimiter(int capacity, double refillRate) {
        this(capacity, refillRate, Clock.systemUTC());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Algorithm steps:
     * <ol>
     *   <li>Look up (or lazily create) the bucket for {@code clientId}.
     *       New buckets start <strong>full</strong> (tokens = capacity).</li>
     *   <li>Compute elapsed time since the last refill and add
     *       {@code elapsed_seconds * refillRate} tokens, capping at capacity.</li>
     *   <li>If tokens &ge; 1, consume one token and return {@code true}.</li>
     *   <li>Otherwise return {@code false}.</li>
     * </ol>
     */
    @Override
    public boolean tryAcquire(String clientId) {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;

        // computeIfAbsent is atomic: even if 50 threads arrive for a new clientId
        // simultaneously, exactly one Bucket is created and all threads share it.
        // A naive "get → check null → put" would race and could lose a Bucket.
        Bucket bucket = buckets.computeIfAbsent(clientId,
                k -> new Bucket(capacity, nowNanos));

        // Fine-grained lock: only threads accessing the SAME client block each
        // other. Threads for different clients proceed in parallel.
        synchronized (bucket) {
            // Refill tokens based on elapsed time.
            long elapsedNanos = nowNanos - bucket.lastRefillNanos;
            if (elapsedNanos > 0) {
                double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
                double newTokens = elapsedSeconds * refillRate;
                bucket.tokens = Math.min(capacity, bucket.tokens + newTokens);
                bucket.lastRefillNanos = nowNanos;
            }

            // Try to consume one token.
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
