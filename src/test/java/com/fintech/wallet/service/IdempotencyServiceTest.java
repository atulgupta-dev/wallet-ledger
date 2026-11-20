package com.fintech.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.wallet.dto.DepositResponse;
import com.fintech.wallet.entity.TransactionStatus;
import com.fintech.wallet.exception.IdempotencyConflictException;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.exception.InvalidTransferException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private UUID userId;
    private String idempotencyKey;
    private String requestHash;
    private String redisKey;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idempotencyService = new IdempotencyService(redisTemplate);

        userId = UUID.randomUUID();
        idempotencyKey = "KEY-12345";
        requestHash = idempotencyService.computePayloadHash(5000L, null, "test deposit");
        redisKey = "idempotency:" + userId + ":" + idempotencyKey;
    }

    @Test
    void shouldExecuteNewRequestAndCacheCompletedResponse() {
        when(valueOperations.setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);

        DepositResponse expectedResponse = new DepositResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TransactionStatus.COMPLETED,
                5000L,
                "INR",
                UUID.randomUUID(),
                5000L,
                Instant.now()
        );

        IdempotencyResult<DepositResponse> result = idempotencyService.execute(
                userId,
                idempotencyKey,
                requestHash,
                DepositResponse.class,
                () -> expectedResponse,
                null
        );

        assertNotNull(result);
        assertFalse(result.isReplay());
        assertEquals(5000L, result.response().amount());

        verify(valueOperations).set(eq(redisKey), anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldReturnCachedResponseOnIdempotentReplay() throws Exception {
        when(valueOperations.setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);

        DepositResponse cachedDeposit = new DepositResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TransactionStatus.COMPLETED,
                5000L,
                "INR",
                UUID.randomUUID(),
                5000L,
                Instant.now()
        );

        IdempotencyRecord completedRecord = new IdempotencyRecord(
                "COMPLETED",
                requestHash,
                objectMapper.writeValueAsString(cachedDeposit),
                DepositResponse.class.getName()
        );

        when(valueOperations.get(redisKey)).thenReturn(objectMapper.writeValueAsString(completedRecord));

        IdempotencyResult<DepositResponse> result = idempotencyService.execute(
                userId,
                idempotencyKey,
                requestHash,
                DepositResponse.class,
                () -> { throw new RuntimeException("DB should not be touched on replay!"); },
                null
        );

        assertNotNull(result);
        assertTrue(result.isReplay());
        assertEquals(5000L, result.response().amount());
    }

    @Test
    void shouldThrowIdempotencyConflictExceptionWhenRequestIsInProgress() throws Exception {
        when(valueOperations.setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);

        IdempotencyRecord inProgressRecord = new IdempotencyRecord(
                "IN_PROGRESS",
                requestHash,
                null,
                DepositResponse.class.getName()
        );
        when(valueOperations.get(redisKey)).thenReturn(objectMapper.writeValueAsString(inProgressRecord));

        assertThrows(IdempotencyConflictException.class, () ->
                idempotencyService.execute(
                        userId,
                        idempotencyKey,
                        requestHash,
                        DepositResponse.class,
                        () -> null,
                        null
                ));
    }

    @Test
    void shouldThrowInvalidTransferExceptionWhenSameKeyReusedWithDifferentPayload() throws Exception {
        when(valueOperations.setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(false);

        IdempotencyRecord completedRecord = new IdempotencyRecord(
                "COMPLETED",
                "DIFFERENT_HASH_VALUE",
                "{}",
                DepositResponse.class.getName()
        );
        when(valueOperations.get(redisKey)).thenReturn(objectMapper.writeValueAsString(completedRecord));

        assertThrows(InvalidTransferException.class, () ->
                idempotencyService.execute(
                        userId,
                        idempotencyKey,
                        requestHash,
                        DepositResponse.class,
                        () -> null,
                        null
                ));
    }

    @Test
    void shouldDeleteKeyFromRedisOnActionFailureSoUserCanRetry() {
        when(valueOperations.setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30)))).thenReturn(true);

        assertThrows(InsufficientFundsException.class, () ->
                idempotencyService.execute(
                        userId,
                        idempotencyKey,
                        requestHash,
                        DepositResponse.class,
                        () -> { throw new InsufficientFundsException("Balance too low"); },
                        null
                ));

        verify(redisTemplate).delete(redisKey);
    }
}
