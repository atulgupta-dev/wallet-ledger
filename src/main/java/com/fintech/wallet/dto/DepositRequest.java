package com.fintech.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DepositRequest(
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be strictly greater than zero")
    Long amount,

    String idempotencyKey,

    String description
) {}
