package com.fintech.wallet.dto;

import com.fintech.wallet.entity.WalletStatus;

import java.util.UUID;

public record WalletSummary(
    UUID walletId,
    String currency,
    Long balance,
    WalletStatus status
) {}
