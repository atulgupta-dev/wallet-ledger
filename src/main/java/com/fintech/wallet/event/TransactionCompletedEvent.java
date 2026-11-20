package com.fintech.wallet.event;

import com.fintech.wallet.entity.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record TransactionCompletedEvent(
    UUID eventId,
    UUID transactionId,
    UUID referenceId,
    TransactionType type,
    Long amount,
    String currency,
    UUID sourceWalletId,
    UUID destinationWalletId,
    Instant timestamp
) {}
