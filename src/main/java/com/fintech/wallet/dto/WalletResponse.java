package com.fintech.wallet.dto;

import com.fintech.wallet.entity.WalletStatus;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
    UUID walletId,
    UUID userId,
    String currency,
    Long balance,
    WalletStatus status,
    Instant createdAt,
    Instant updatedAt
) {}
