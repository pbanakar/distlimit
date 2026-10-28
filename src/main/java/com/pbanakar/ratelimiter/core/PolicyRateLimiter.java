package com.pbanakar.ratelimiter.core;

import java.util.Map;

/**
 * Routes requests to independently configured rate limiters by policy name.
 */
public class PolicyRateLimiter implements RateLimiter {

    private final Map<String, RateLimiter> policies;

    public PolicyRateLimiter(Map<String, RateLimiter> policies) {
        if (policies == null || policies.isEmpty()) {
            throw new IllegalArgumentException("At least one rate-limit policy is required");
        }
        this.policies = Map.copyOf(policies);
    }

    @Override
    public boolean tryAcquire(String clientId) {
        return tryAcquire(clientId, "default");
    }

    @Override
    public boolean tryAcquire(String clientId, String policyName) {
        RateLimiter limiter = policies.get(policyName);
        if (limiter == null) {
            throw new IllegalArgumentException("Unknown rate-limit policy: " + policyName);
        }
        return limiter.tryAcquire(clientId);
    }
}
