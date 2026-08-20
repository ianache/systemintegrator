package com.cl2.integration.integration.resilience;

public record RateLimitResult(
    boolean allowed,
    long remainingTokens,
    long resetAfterSeconds
) {}
