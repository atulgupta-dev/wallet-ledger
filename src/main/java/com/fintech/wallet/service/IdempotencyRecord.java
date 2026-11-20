package com.fintech.wallet.service;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdempotencyRecord(
    String status,
    String requestHash,
    String responseJson,
    String responseClassName
) {}
