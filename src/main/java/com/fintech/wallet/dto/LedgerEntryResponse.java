package com.fintech.wallet.dto;

import com.fintech.wallet.entity.EntryType;
import com.fintech.wallet.entity.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
    UUID entryId,
    UUID transactionId,
    UUID referenceId,
    EntryType entryType,
    TransactionType transactionType,
    Long amount,
    Long balanceAfter,
    String description,
    Instant createdAt
) {}
