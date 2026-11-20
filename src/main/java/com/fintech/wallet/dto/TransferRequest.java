package com.fintech.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferRequest(
    @NotNull(message = "Destination wallet ID is required")
    UUID destinationWalletId,

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be strictly greater than zero")
    Long amount,

    String description
) {}
