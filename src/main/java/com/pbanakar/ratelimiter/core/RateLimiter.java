package com.pbanakar.ratelimiter.core;

/**
 * Common interface for all rate-limiting algorithms.
 *
 * <p>Each implementation tracks per-client state internally, so a single
 * {@code RateLimiter} instance can serve many clients simultaneously.
 * In Phase 1 the implementations are <strong>not</strong> thread-safe;
 * concurrency support will be added in Phase 2.
 */
public interface RateLimiter {

    /**
     * Attempts to acquire permission for one request from the given client.
     *
     * @param clientId a non-null identifier for the client (e.g. API key, IP address)
     * @return {@code true} if the request is allowed; {@code false} if it should be rejected
     */
    boolean tryAcquire(String clientId);
}
