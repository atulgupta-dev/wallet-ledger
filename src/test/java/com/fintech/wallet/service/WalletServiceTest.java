package com.fintech.wallet.service;

import com.fintech.wallet.dto.DepositRequest;
import com.fintech.wallet.dto.DepositResponse;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.entity.EntryType;
import com.fintech.wallet.entity.LedgerEntry;
import com.fintech.wallet.entity.Transaction;
import com.fintech.wallet.entity.TransactionStatus;
import com.fintech.wallet.entity.TransactionType;
import com.fintech.wallet.entity.User;
import com.fintech.wallet.entity.Wallet;
import com.fintech.wallet.entity.WalletStatus;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.exception.InvalidTransferException;
import com.fintech.wallet.exception.ResourceNotFoundException;
import com.fintech.wallet.repository.LedgerEntryRepository;
import com.fintech.wallet.repository.TransactionRepository;
import com.fintech.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WalletService walletService;

    private User senderUser;
    private User recipientUser;
    private Wallet senderWallet;
    private Wallet recipientWallet;

    @BeforeEach
    void setUp() {
        UUID senderUserId = UUID.randomUUID();
        UUID recipientUserId = UUID.randomUUID();

        senderUser = User.builder().id(senderUserId).email("sender@fintech.com").fullName("Sender").build();
        recipientUser = User.builder().id(recipientUserId).email("recipient@fintech.com").fullName("Recipient").build();

        // Create deterministic UUIDs for sender and recipient wallets
        UUID uuid1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID uuid2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

        senderWallet = Wallet.builder()
                .id(uuid2)
                .user(senderUser)
                .currency("INR")
                .balance(50000L) // 500 INR in paise
                .status(WalletStatus.ACTIVE)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        recipientWallet = Wallet.builder()
                .id(uuid1)
                .user(recipientUser)
                .currency("INR")
                .balance(10000L) // 100 INR in paise
                .status(WalletStatus.ACTIVE)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void shouldDepositFundsSuccessfullyWithPessimisticLock() {
        DepositRequest request = new DepositRequest(25000L, "DEP-123", "Initial wallet funding");

        when(walletRepository.findWalletIdByUserId(senderUser.getId())).thenReturn(Optional.of(senderWallet.getId()));
        when(transactionRepository.findByIdempotencyKey("DEP-123")).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            return Transaction.builder()
                    .id(UUID.randomUUID())
                    .referenceId(tx.getReferenceId())
                    .idempotencyKey(tx.getIdempotencyKey())
                    .type(tx.getType())
                    .status(tx.getStatus())
                    .amount(tx.getAmount())
                    .currency(tx.getCurrency())
                    .destinationWallet(tx.getDestinationWallet())
                    .createdAt(Instant.now())
                    .build();
        });

        DepositResponse response = walletService.deposit(senderUser.getId(), request, "DEP-123");

        assertNotNull(response);
        assertEquals(75000L, response.newBalance()); // 50000 + 25000
        assertEquals(TransactionStatus.COMPLETED, response.status());

        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(entryCaptor.capture());
        LedgerEntry creditEntry = entryCaptor.getValue();
        assertEquals(EntryType.CREDIT, creditEntry.getEntryType());
        assertEquals(25000L, creditEntry.getAmount());
        assertEquals(75000L, creditEntry.getBalanceAfter());
    }

    @Test
    void shouldExecuteTransferWithDeadlockPreventionLockOrdering() {
        TransferRequest request = new TransferRequest(recipientWallet.getId(), 20000L, "Payment for services");

        when(walletRepository.findWalletIdByUserId(senderUser.getId())).thenReturn(Optional.of(senderWallet.getId()));
        when(transactionRepository.findByIdempotencyKey("TX-789")).thenReturn(Optional.empty());

        // senderWallet id is 2222... recipientWallet id is 1111...
        // Smaller UUID is 1111... (recipientWallet), so it must be locked FIRST
        when(walletRepository.findByIdForUpdate(recipientWallet.getId())).thenReturn(Optional.of(recipientWallet));
        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            return Transaction.builder()
                    .id(UUID.randomUUID())
                    .referenceId(tx.getReferenceId())
                    .idempotencyKey(tx.getIdempotencyKey())
                    .type(tx.getType())
                    .status(tx.getStatus())
                    .amount(tx.getAmount())
                    .currency(tx.getCurrency())
                    .sourceWallet(tx.getSourceWallet())
                    .destinationWallet(tx.getDestinationWallet())
                    .createdAt(Instant.now())
                    .build();
        });

        TransferResponse response = walletService.transfer(senderUser.getId(), request, "TX-789");

        assertNotNull(response);
        assertEquals(30000L, response.newBalance()); // 50000 - 20000
        assertEquals(30000L, recipientWallet.getBalance()); // 10000 + 20000

        // Verify InOrder lock acquisition: smaller UUID locked first!
        InOrder inOrder = Mockito.inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdForUpdate(recipientWallet.getId()); // 1111...
        inOrder.verify(walletRepository).findByIdForUpdate(senderWallet.getId());    // 2222...

        // Verify two ledger entries saved: 1 DEBIT, 1 CREDIT
        ArgumentCaptor<LedgerEntry> entriesCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(entriesCaptor.capture());

        LedgerEntry debitEntry = entriesCaptor.getAllValues().get(0);
        assertEquals(EntryType.DEBIT, debitEntry.getEntryType());
        assertEquals(20000L, debitEntry.getAmount());
        assertEquals(30000L, debitEntry.getBalanceAfter());

        LedgerEntry creditEntry = entriesCaptor.getAllValues().get(1);
        assertEquals(EntryType.CREDIT, creditEntry.getEntryType());
        assertEquals(20000L, creditEntry.getAmount());
        assertEquals(30000L, creditEntry.getBalanceAfter());
    }

    @Test
    void shouldThrowInvalidTransferExceptionOnSelfTransfer() {
        TransferRequest request = new TransferRequest(senderWallet.getId(), 5000L, "Self transfer");
        when(walletRepository.findWalletIdByUserId(senderUser.getId())).thenReturn(Optional.of(senderWallet.getId()));

        assertThrows(InvalidTransferException.class, () ->
                walletService.transfer(senderUser.getId(), request, "TX-SELF"));

        verify(walletRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void shouldThrowInsufficientFundsExceptionWhenSenderBalanceIsTooLow() {
        TransferRequest request = new TransferRequest(recipientWallet.getId(), 100000L, "Overdraft"); // 100000 > 50000
        when(walletRepository.findWalletIdByUserId(senderUser.getId())).thenReturn(Optional.of(senderWallet.getId()));
        when(transactionRepository.findByIdempotencyKey("TX-FAIL")).thenReturn(Optional.empty());
        when(walletRepository.findByIdForUpdate(recipientWallet.getId())).thenReturn(Optional.of(recipientWallet));
        when(walletRepository.findByIdForUpdate(senderWallet.getId())).thenReturn(Optional.of(senderWallet));

        assertThrows(InsufficientFundsException.class, () ->
                walletService.transfer(senderUser.getId(), request, "TX-FAIL"));

        verify(transactionRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).save(any());
    }
}
