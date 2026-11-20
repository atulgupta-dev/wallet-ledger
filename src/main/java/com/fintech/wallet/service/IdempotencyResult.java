package com.fintech.wallet.service;

public record IdempotencyResult<T>(
    T response,
    boolean isReplay
) {}
