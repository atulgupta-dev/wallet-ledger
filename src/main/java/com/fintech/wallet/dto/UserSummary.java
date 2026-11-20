package com.fintech.wallet.dto;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(
    UUID id,
    String email,
    String fullName,
    Instant createdAt
) {}
