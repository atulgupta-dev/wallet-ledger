package com.fintech.wallet.integration;

import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.entity.Transaction;
import com.fintech.wallet.entity.User;
import com.fintech.wallet.entity.Wallet;
import com.fintech.wallet.exception.IdempotencyConflictException;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Wallet Concurrency & Integration Tests")
class ConcurrentTransferIntegrationTest extends BaseIntegrationTest {

    /**
     * Test 1 — Concurrent Overdraft Prevention:
     * - User A starts with 100000 (₹1,000.00), User B with 0.
     * - 20 simultaneous threads fire transfers of 10000 (₹100.00) each from User A to User B.
     * - Exactly 10 transfers succeed (200 OK / non-exceptional), 10 fail with InsufficientFundsException.
     * - User A's final balance is 0, User B's final balance is 100000.
     * - SUM(CREDIT) - SUM(DEBIT) == 0 across the entire ledger.
     */
    @Test
    @DisplayName("Test 1 — Concurrent Overdraft Prevention")
    void testConcurrentOverdraftPrevention() throws InterruptedException {
        long initialBalanceA = 100000L;
        long transferAmount = 10000L;
        int totalThreads = 20;

        User userA = createTestUserWithWallet("userA_overdraft@fintech.com", "User A", initialBalanceA);
        User userB = createTestUserWithWallet("userB_overdraft@fintech.com", "User B", 0L);

        UUID userAId = userA.getId();
        UUID userBWalletId = walletRepository.findByUser_Id(userB.getId()).orElseThrow().getId();

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            final String idempotencyKey = "overdraft-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    TransferRequest request = new TransferRequest(userBWalletId, transferAmount, "Overdraft test");
                    walletService.transfer(userAId, request, idempotencyKey);
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException ife) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrorCount.incrementAndGet();
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Wait for all threads to be ready, then trigger simultaneous execution
        assertTrue(readyLatch.await(10, TimeUnit.SECONDS), "All threads must be ready before firing");
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(completed, "All 20 transfer threads must complete within 30 seconds");

        // Assertions
        assertEquals(0, unexpectedErrorCount.get(), "No unexpected errors should occur: " + unexpectedErrors);
        assertEquals(10, successCount.get(), "Exactly 10 transfers must succeed");
        assertEquals(10, insufficientFundsCount.get(), "Exactly 10 transfers must fail with InsufficientFundsException");

        Wallet finalWalletA = walletRepository.findByUser_Id(userAId).orElseThrow();
        Wallet finalWalletB = walletRepository.findByUser_Id(userB.getId()).orElseThrow();

        assertEquals(0L, finalWalletA.getBalance(), "User A's final balance must be strictly 0");
        assertEquals(100000L, finalWalletB.getBalance(), "User B's final balance must be strictly 100000");

        // Querying ledger_entries confirms SUM(CREDIT) - SUM(DEBIT) == 0 across the entire ledger
        Long netLedgerBalance = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_entries",
                Long.class
        );
        assertNotNull(netLedgerBalance);
        assertEquals(0L, netLedgerBalance, "SUM(CREDIT) - SUM(DEBIT) must strictly equal 0 across the entire ledger");

        // Exactly 20 ledger entries: 10 debits on source wallet, 10 credits on destination wallet
        Long totalLedgerEntries = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertEquals(20L, totalLedgerEntries, "Exactly 20 ledger entries must exist (10 debits and 10 credits)");

        Long totalTransactions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
        assertEquals(10L, totalTransactions, "Exactly 10 completed transaction records must exist");
    }

    /**
     * Test 2 — Bidirectional Deadlock Stress Test:
     * - User A and User B both start with 1000000 (₹10,000.00).
     * - 50 threads (25 A->B and 25 B->A) fire simultaneous transfers of 1000 each at the exact same millisecond.
     * - Assert zero deadlocks occur, all 50 transfers succeed, and walletA.balance + walletB.balance remains strictly 2000000.
     */
    @Test
    @DisplayName("Test 2 — Bidirectional Deadlock Stress Test")
    void testBidirectionalDeadlockStress() throws InterruptedException {
        long initialBalance = 1000000L;
        long transferAmount = 1000L;
        int transfersPerDirection = 25;
        int totalThreads = transfersPerDirection * 2; // 50 threads

        User userA = createTestUserWithWallet("userA_deadlock@fintech.com", "User A", initialBalance);
        User userB = createTestUserWithWallet("userB_deadlock@fintech.com", "User B", initialBalance);

        UUID userAId = userA.getId();
        UUID userBId = userB.getId();
        UUID walletAId = walletRepository.findByUser_Id(userAId).orElseThrow().getId();
        UUID walletBId = walletRepository.findByUser_Id(userBId).orElseThrow().getId();

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // 25 transfers A -> B
        for (int i = 0; i < transfersPerDirection; i++) {
            final String idempotencyKey = "deadlock-a-b-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    TransferRequest request = new TransferRequest(walletBId, transferAmount, "A to B transfer");
                    walletService.transfer(userAId, request, idempotencyKey);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 25 transfers B -> A
        for (int i = 0; i < transfersPerDirection; i++) {
            final String idempotencyKey = "deadlock-b-a-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    TransferRequest request = new TransferRequest(walletAId, transferAmount, "B to A transfer");
                    walletService.transfer(userBId, request, idempotencyKey);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS), "All 50 threads must be ready before firing");
        startLatch.countDown();
        boolean completed = doneLatch.await(45, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(completed, "All 50 transfer threads must complete within timeout");

        // Zero deadlocks occurred and all 50 transfers succeeded
        assertEquals(0, errors.size(), "Zero deadlocks and zero exceptions must occur: " + errors);
        assertEquals(50, successCount.get(), "All 50 transfers must succeed");

        Wallet finalWalletA = walletRepository.findByUser_Id(userAId).orElseThrow();
        Wallet finalWalletB = walletRepository.findByUser_Id(userBId).orElseThrow();

        assertEquals(1000000L, finalWalletA.getBalance(), "User A's balance should be 1000000 (net zero change)");
        assertEquals(1000000L, finalWalletB.getBalance(), "User B's balance should be 1000000 (net zero change)");
        assertEquals(
                2000000L,
                finalWalletA.getBalance() + finalWalletB.getBalance(),
                "walletA.balance + walletB.balance remains strictly conserved at 2000000"
        );

        Long totalTransactions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
        assertEquals(50L, totalTransactions, "Exactly 50 transaction records must exist");

        Long totalLedgerEntries = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        assertEquals(100L, totalLedgerEntries, "Exactly 100 ledger entries (50 debits + 50 credits) must exist");
    }

    /**
     * Test 3 — Concurrent Idempotency Race Condition Test:
     * - Fire 10 simultaneous transfer requests from User A to User B with the exact same Idempotency-Key.
     * - Assert that User A is debited only once, User B is credited only once, only 1 Transaction row exists in PostgreSQL
     *   for that key, and every successful response returns the exact same transactionId.
     */
    @Test
    @DisplayName("Test 3 — Concurrent Idempotency Race Condition Test")
    void testConcurrentIdempotencyRaceCondition() throws InterruptedException {
        long initialBalanceA = 50000L;
        long transferAmount = 10000L;
        int totalThreads = 10;
        String sharedIdempotencyKey = "idemp-race-" + UUID.randomUUID();

        User userA = createTestUserWithWallet("userA_idemp@fintech.com", "User A", initialBalanceA);
        User userB = createTestUserWithWallet("userB_idemp@fintech.com", "User B", 0L);

        UUID userAId = userA.getId();
        UUID userBWalletId = walletRepository.findByUser_Id(userB.getId()).orElseThrow().getId();
        UserPrincipal principalA = new UserPrincipal(userAId, userA.getEmail());

        TransferRequest transferRequest = new TransferRequest(userBWalletId, transferAmount, "Idempotency race test");

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        List<ResponseEntity<TransferResponse>> successfulResponses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inProgressConflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<TransferResponse> response =
                            walletController.transfer(principalA, sharedIdempotencyKey, transferRequest);
                    successfulResponses.add(response);
                } catch (IdempotencyConflictException ice) {
                    // 409 Conflict rejection for in-progress duplicate request is expected and correct
                    inProgressConflictCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS), "All 10 threads must be ready before firing");
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(completed, "All 10 threads must complete within timeout");

        assertEquals(0, unexpectedErrors.size(), "No unexpected errors should occur: " + unexpectedErrors);
        assertTrue(successfulResponses.size() >= 1, "At least 1 request must succeed");

        // Every successful response returns the exact same transactionId
        UUID expectedTransactionId = successfulResponses.get(0).getBody().transactionId();
        assertNotNull(expectedTransactionId, "Transaction ID must not be null");

        for (ResponseEntity<TransferResponse> response : successfulResponses) {
            assertNotNull(response.getBody());
            assertEquals(
                    expectedTransactionId,
                    response.getBody().transactionId(),
                    "Every successful response must return the exact same transactionId"
            );
        }

        // Subsequent sequential call with the same key should return the idempotent cached replay
        ResponseEntity<TransferResponse> replayResponse =
                walletController.transfer(principalA, sharedIdempotencyKey, transferRequest);
        assertNotNull(replayResponse.getBody());
        assertEquals(expectedTransactionId, replayResponse.getBody().transactionId(), "Replay must return the same transaction ID");
        assertEquals("true", replayResponse.getHeaders().getFirst("X-Idempotent-Replay"), "Replay header must be set");

        // Assert balances: User A debited only once, User B credited only once
        Wallet finalWalletA = walletRepository.findByUser_Id(userAId).orElseThrow();
        Wallet finalWalletB = walletRepository.findByUser_Id(userB.getId()).orElseThrow();

        assertEquals(initialBalanceA - transferAmount, finalWalletA.getBalance(), "User A must be debited exactly once");
        assertEquals(transferAmount, finalWalletB.getBalance(), "User B must be credited exactly once");

        // Only 1 Transaction row exists in PostgreSQL for that key
        Long txCountForKey = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?",
                Long.class,
                sharedIdempotencyKey
        );
        assertEquals(1L, txCountForKey, "Only 1 Transaction row must exist in PostgreSQL for the idempotency key");

        Optional<Transaction> txOpt = transactionRepository.findByIdempotencyKey(sharedIdempotencyKey);
        assertTrue(txOpt.isPresent(), "Transaction must exist in repository");
        assertEquals(expectedTransactionId, txOpt.get().getId(), "Stored transaction ID must match response transaction ID");

        // Exactly 2 ledger entries exist for this transaction (1 DEBIT on source, 1 CREDIT on destination)
        Long ledgerCountForTx = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Long.class,
                expectedTransactionId
        );
        assertEquals(2L, ledgerCountForTx, "Exactly 2 ledger entries must exist for the transaction (1 debit, 1 credit)");
    }
}
