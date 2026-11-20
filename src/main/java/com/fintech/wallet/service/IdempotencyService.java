package com.fintech.wallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.wallet.exception.IdempotencyConflictException;
import com.fintech.wallet.exception.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public String computePayloadHash(Long amount, UUID destinationWalletId, String description) {
        String raw = (amount != null ? amount.toString() : "") + ":" +
                (destinationWalletId != null ? destinationWalletId.toString() : "") + ":" +
                (description != null ? description.trim() : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public <T> IdempotencyResult<T> execute(
            UUID userId,
            String idempotencyKey,
            String requestHash,
            Class<T> responseClass,
            Supplier<T> transactionalAction,
            Supplier<T> databaseFallbackQuery) {

        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 128) {
            throw new InvalidTransferException("A non-blank Idempotency-Key header is required (maximum 128 characters)");
        }

        String key = idempotencyKey.trim();
        String redisKey = "idempotency:" + userId + ":" + key;

        IdempotencyRecord inProgressRecord = new IdempotencyRecord(
                "IN_PROGRESS",
                requestHash,
                null,
                responseClass.getName()
        );

        String inProgressJson;
        try {
            inProgressJson = objectMapper.writeValueAsString(inProgressRecord);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Error serializing idempotency record", e);
        }

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, inProgressJson, LOCK_TTL);

        if (Boolean.FALSE.equals(acquired)) {
            String existingJson = redisTemplate.opsForValue().get(redisKey);
            if (existingJson != null) {
                try {
                    IdempotencyRecord record = objectMapper.readValue(existingJson, IdempotencyRecord.class);
                    if ("IN_PROGRESS".equals(record.status())) {
                        throw new IdempotencyConflictException(
                                "A request with idempotency key '" + key + "' is currently in progress. Please retry shortly."
                        );
                    }
                    if ("COMPLETED".equals(record.status())) {
                        if (!requestHash.equals(record.requestHash())) {
                            throw new InvalidTransferException(
                                    "Idempotency key '" + key + "' was previously used with a different request payload"
                            );
                        }
                        T cachedResponse = objectMapper.readValue(record.responseJson(), responseClass);
                        log.info("Idempotent replay served from Redis for key: {}", redisKey);
                        return new IdempotencyResult<>(cachedResponse, true);
                    }
                } catch (JsonProcessingException e) {
                    log.error("Failed to parse cached idempotency record for key {}", redisKey, e);
                }
            }
        }

        // Successfully acquired lock (or key expired concurrently)
        try {
            T response;
            try {
                response = transactionalAction.get();
            } catch (DataIntegrityViolationException dive) {
                // Database fallback if key was evicted from Redis but exists in Postgres
                if (databaseFallbackQuery != null) {
                    T fallbackResponse = databaseFallbackQuery.get();
                    if (fallbackResponse != null) {
                        saveCompletedRecord(redisKey, requestHash, fallbackResponse, responseClass);
                        return new IdempotencyResult<>(fallbackResponse, true);
                    }
                }
                throw dive;
            }

            saveCompletedRecord(redisKey, requestHash, response, responseClass);
            return new IdempotencyResult<>(response, false);

        } catch (Exception ex) {
            // Delete IN_PROGRESS key on failure so the client can safely retry
            redisTemplate.delete(redisKey);
            throw ex;
        }
    }

    private <T> void saveCompletedRecord(String redisKey, String requestHash, T response, Class<T> responseClass) {
        try {
            String serializedResponse = objectMapper.writeValueAsString(response);
            IdempotencyRecord completedRecord = new IdempotencyRecord(
                    "COMPLETED",
                    requestHash,
                    serializedResponse,
                    responseClass.getName()
            );
            redisTemplate.opsForValue().set(
                    redisKey,
                    objectMapper.writeValueAsString(completedRecord),
                    COMPLETED_TTL
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize completed idempotency response for key {}", redisKey, e);
        }
    }
}
