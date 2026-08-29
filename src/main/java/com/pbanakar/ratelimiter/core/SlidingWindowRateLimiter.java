package com.pbanakar.ratelimiter.core;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Rate limiter that uses the <strong>Sliding Window Log</strong> algorithm.
 *
 * <h3>How it works</h3>
 * <p>For each client we maintain a log (deque) of the timestamps of every
 * allowed request. On each call to {@link #tryAcquire(String)}:
 * <ol>
 *   <li>Evict all timestamps older than {@code now - windowSizeMillis}
 *       (the comparison is <strong>strictly less-than</strong>, meaning a
 *       timestamp exactly equal to the window boundary is kept — the window
 *       is <em>left-exclusive, right-inclusive</em>: {@code (now - window, now]}).</li>
 *   <li>If the remaining count is below {@code maxRequests}, record the current
 *       timestamp and allow the request.</li>
 *   <li>Otherwise, reject.</li>
 * </ol>
 *
 * <h3>Window boundary semantics — design decision</h3>
 * <p>The window is defined as <strong>(now − windowSizeMillis, now]</strong>.
 * A request whose timestamp is exactly {@code now − windowSizeMillis} is
 * considered <em>outside</em> the window and is evicted. This means the
 * window contains timestamps strictly newer than the boundary. This choice
 * keeps the maths simple and avoids off-by-one ambiguity — a request made
 * exactly {@code windowSizeMillis} ago has "expired".</p>
 *
 * <h3>Trade-offs</h3>
 * <ul>
 *   <li><strong>Precise</strong> — every request is tracked individually, so
 *       there is no approximation error that counter-based approaches suffer
 *       from.</li>
 *   <li><strong>Higher memory per client</strong> — we store up to
 *       {@code maxRequests} timestamps per client, whereas Token Bucket stores
 *       only two numbers. For high-throughput clients or large limits this can
 *       add up.</li>
 *   <li><strong>No burst allowance</strong> — unlike Token Bucket, an idle
 *       client doesn't accumulate "credit". The limit is always enforced over
 *       the most recent window.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>This implementation is <strong>not</strong> thread-safe. Concurrent access
 * will be addressed in Phase 2.</p>
 *
 * @see RateLimiter
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowSizeMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> clientLogs;

    /**
     * Creates a new Sliding Window Log rate limiter.
     *
     * @param maxRequests      maximum number of requests allowed within the window
     * @param windowSizeMillis length of the sliding window in milliseconds
     * @param clock            time source — inject a fixed or manually-advancing
     *                         clock in tests for deterministic behaviour
     * @throws IllegalArgumentException if maxRequests &lt; 1 or windowSizeMillis &lt; 1
     */
    public SlidingWindowRateLimiter(int maxRequests, long windowSizeMillis, Clock clock) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be >= 1, got " + maxRequests);
        }
        if (windowSizeMillis < 1) {
            throw new IllegalArgumentException("windowSizeMillis must be >= 1, got " + windowSizeMillis);
        }
        this.maxRequests = maxRequests;
        this.windowSizeMillis = windowSizeMillis;
        this.clock = clock;
        this.clientLogs = new HashMap<>();
    }

    /**
     * Convenience constructor that uses the system UTC clock.
     *
     * @param maxRequests      maximum requests per window
     * @param windowSizeMillis window length in milliseconds
     */
    public SlidingWindowRateLimiter(int maxRequests, long windowSizeMillis) {
        this(maxRequests, windowSizeMillis, Clock.systemUTC());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Algorithm steps:
     * <ol>
     *   <li>Look up (or lazily create) the request log for {@code clientId}.</li>
     *   <li>Compute the window boundary: {@code now - windowSizeMillis}.</li>
     *   <li>Evict all timestamps {@code <= boundary} from the front of the deque
     *       (they are outside the window).</li>
     *   <li>If the remaining count {@code < maxRequests}, record {@code now} and
     *       return {@code true}.</li>
     *   <li>Otherwise return {@code false}.</li>
     * </ol>
     */
    @Override
    public boolean tryAcquire(String clientId) {
        long nowMillis = clock.instant().toEpochMilli();
        long boundary = nowMillis - windowSizeMillis;

        Deque<Long> log = clientLogs.computeIfAbsent(clientId, k -> new ArrayDeque<>());

        // Evict expired entries. The window is (boundary, now], so timestamps
        // exactly equal to `boundary` are expired and removed.
        while (!log.isEmpty() && log.peekFirst() <= boundary) {
            log.pollFirst();
        }

        if (log.size() < maxRequests) {
            log.addLast(nowMillis);
            return true;
        }

        return false;
    }
}
