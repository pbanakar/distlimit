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

    /**
     * Attempts to acquire permission using a named policy.
     *
     * <p>Single-policy implementations continue to use their existing
     * configuration; policy-aware implementations can override this method.</p>
     *
     * @param clientId a non-null identifier for the client
     * @param policyName configured policy name
     * @return {@code true} if the request is allowed
     */
    default boolean tryAcquire(String clientId, String policyName) {
        return tryAcquire(clientId);
    }
}
