package com.fintech.wallet.dto;

import com.fintech.wallet.entity.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
    UUID transactionId,
    UUID referenceId,
    TransactionStatus status,
    Long amount,
    String currency,
    UUID sourceWalletId,
    UUID destinationWalletId,
    Long newBalance,
    Instant createdAt
) {}
