package com.fintech.wallet.service;

import com.fintech.wallet.dto.DepositRequest;
import com.fintech.wallet.dto.DepositResponse;
import com.fintech.wallet.dto.LedgerEntryResponse;
import com.fintech.wallet.dto.PageResponse;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.dto.WalletResponse;
import com.fintech.wallet.entity.EntryType;
import com.fintech.wallet.entity.LedgerEntry;
import com.fintech.wallet.entity.Transaction;
import com.fintech.wallet.entity.TransactionStatus;
import com.fintech.wallet.entity.TransactionType;
import com.fintech.wallet.entity.Wallet;
import com.fintech.wallet.entity.WalletStatus;
import com.fintech.wallet.event.TransactionCompletedEvent;
import com.fintech.wallet.exception.InsufficientFundsException;
import com.fintech.wallet.exception.InvalidTransferException;
import com.fintech.wallet.exception.ResourceNotFoundException;
import com.fintech.wallet.repository.LedgerEntryRepository;
import com.fintech.wallet.repository.TransactionRepository;
import com.fintech.wallet.repository.WalletRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final com.fintech.wallet.metrics.WalletMetrics walletMetrics;

    public WalletService(
            WalletRepository walletRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            ApplicationEventPublisher eventPublisher) {
        this(walletRepository, transactionRepository, ledgerEntryRepository, eventPublisher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WalletService(
            WalletRepository walletRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            ApplicationEventPublisher eventPublisher,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.fintech.wallet.metrics.WalletMetrics walletMetrics) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.eventPublisher = eventPublisher;
        this.walletMetrics = walletMetrics;
    }

    private void recordTransferSuccess(Long amount) {
        if (walletMetrics != null) {
            walletMetrics.incrementTransferSuccess(amount);
        }
    }

    private void recordInsufficientFunds() {
        if (walletMetrics != null) {
            walletMetrics.incrementInsufficientFunds();
        }
    }

    private void recordDepositSuccess() {
        if (walletMetrics != null) {
            walletMetrics.incrementDepositSuccess();
        }
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletByUserId(UUID userId) {
        Wallet wallet = walletRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user ID: " + userId));

        return new WalletResponse(
                wallet.getId(),
                wallet.getUser().getId(),
                wallet.getCurrency(),
                wallet.getBalance(),
                wallet.getStatus(),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> getLedgerHistory(
            UUID userId,
            int page,
            int size,
            EntryType entryType,
            Instant fromDate,
            Instant toDate) {

        Wallet wallet = walletRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user ID: " + userId));

        int constrainedSize = Math.max(1, Math.min(size, 100));
        int constrainedPage = Math.max(0, page);
        Pageable pageable = PageRequest.of(constrainedPage, constrainedSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<LedgerEntry> spec = (root, query, cb) -> {
            if (Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("transaction", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("wallet").get("id"), wallet.getId()));
            if (entryType != null) {
                predicates.add(cb.equal(root.get("entryType"), entryType));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<LedgerEntry> entries = ledgerEntryRepository.findAll(spec, pageable);

        Page<LedgerEntryResponse> mappedPage = entries.map(entry -> new LedgerEntryResponse(
                entry.getId(),
                entry.getTransaction().getId(),
                entry.getTransaction().getReferenceId(),
                entry.getEntryType(),
                entry.getTransaction().getType(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getTransaction().getDescription(),
                entry.getCreatedAt()
        ));

        return PageResponse.from(mappedPage);
    }

    @Transactional
    public DepositResponse deposit(UUID userId, DepositRequest request, String idempotencyKey) {
        UUID walletId = walletRepository.findWalletIdByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for user ID: " + userId));

        // Idempotency check: if transaction with this key was already processed, return existing record
        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTx.isPresent()) {
            Transaction tx = existingTx.get();
            Wallet w = walletRepository.findById(walletId).orElse(null);
            Long currentBalance = w != null ? w.getBalance() : 0L;
            return new DepositResponse(
                    tx.getId(),
                    tx.getReferenceId(),
                    tx.getStatus(),
                    tx.getAmount(),
                    tx.getCurrency(),
                    tx.getDestinationWallet().getId(),
                    currentBalance,
                    tx.getCreatedAt()
            );
        }

        // Lock wallet with pessimistic write lock
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with ID: " + walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new InvalidTransferException("Cannot deposit into a wallet with status " + wallet.getStatus());
        }

        wallet.setBalance(wallet.getBalance() + request.amount());
        walletRepository.save(wallet);

        Transaction transaction = Transaction.builder()
                .referenceId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.COMPLETED)
                .amount(request.amount())
                .currency(wallet.getCurrency())
                .sourceWallet(null)
                .destinationWallet(wallet)
                .description(request.description())
                .build();
        Transaction savedTx = transactionRepository.save(transaction);

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transaction(savedTx)
                .wallet(wallet)
                .entryType(EntryType.CREDIT)
                .amount(request.amount())
                .balanceAfter(wallet.getBalance())
                .build();
        ledgerEntryRepository.save(creditEntry);

        // Publish internal Spring application event to trigger post-commit Kafka dispatch
        eventPublisher.publishEvent(new TransactionCompletedEvent(
                UUID.randomUUID(),
                savedTx.getId(),
                savedTx.getReferenceId(),
                savedTx.getType(),
                savedTx.getAmount(),
                savedTx.getCurrency(),
                null,
                wallet.getId(),
                savedTx.getCreatedAt()
        ));

        recordDepositSuccess();

        return new DepositResponse(
                savedTx.getId(),
                savedTx.getReferenceId(),
                savedTx.getStatus(),
                savedTx.getAmount(),
                savedTx.getCurrency(),
                wallet.getId(),
                wallet.getBalance(),
                savedTx.getCreatedAt()
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse transfer(UUID userId, TransferRequest request, String idempotencyKey) {
        // a. Resolve sender's wallet from authenticated JWT user ID
        UUID sourceWalletId = walletRepository.findWalletIdByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Sender wallet not found for user ID: " + userId));

        UUID destinationWalletId = request.destinationWalletId();

        // b. Validate amount > 0 and sourceWalletId != destinationWalletId
        if (request.amount() == null || request.amount() <= 0) {
            throw new InvalidTransferException("Transfer amount must be strictly greater than zero");
        }
        if (sourceWalletId.equals(destinationWalletId)) {
            throw new InvalidTransferException("Self-transfer is not permitted: source and destination wallet are identical");
        }

        // Idempotency check: replay transaction if already completed
        Optional<Transaction> existingTx = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTx.isPresent()) {
            Transaction tx = existingTx.get();
            Wallet sw = walletRepository.findById(sourceWalletId).orElse(null);
            Long currentBalance = sw != null ? sw.getBalance() : 0L;
            return new TransferResponse(
                    tx.getId(),
                    tx.getReferenceId(),
                    tx.getStatus(),
                    tx.getAmount(),
                    tx.getCurrency(),
                    tx.getSourceWallet().getId(),
                    tx.getDestinationWallet().getId(),
                    currentBalance,
                    tx.getCreatedAt()
            );
        }

        // c. Deadlock Prevention: Deterministic hierarchical lock acquisition
        UUID firstLockId;
        UUID secondLockId;
        if (sourceWalletId.compareTo(destinationWalletId) < 0) {
            firstLockId = sourceWalletId;
            secondLockId = destinationWalletId;
        } else {
            firstLockId = destinationWalletId;
            secondLockId = sourceWalletId;
        }

        Wallet firstWallet = walletRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with ID: " + firstLockId));
        Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with ID: " + secondLockId));

        Wallet sourceWallet = sourceWalletId.equals(firstWallet.getId()) ? firstWallet : secondWallet;
        Wallet destinationWallet = destinationWalletId.equals(firstWallet.getId()) ? firstWallet : secondWallet;

        // d. Verify both wallets have status ACTIVE and matching currency
        if (sourceWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new InvalidTransferException("Source wallet is not in ACTIVE status (current: " + sourceWallet.getStatus() + ")");
        }
        if (destinationWallet.getStatus() != WalletStatus.ACTIVE) {
            throw new InvalidTransferException("Destination wallet is not in ACTIVE status (current: " + destinationWallet.getStatus() + ")");
        }
        if (!sourceWallet.getCurrency().equalsIgnoreCase(destinationWallet.getCurrency())) {
            throw new InvalidTransferException("Currency mismatch: Source wallet is " + sourceWallet.getCurrency() + ", but destination wallet is " + destinationWallet.getCurrency());
        }

        // e. Verify sourceWallet.balance >= amount
        if (sourceWallet.getBalance() < request.amount()) {
            recordInsufficientFunds();
            throw new InsufficientFundsException("Insufficient funds. Available: " + sourceWallet.getBalance() + ", Requested: " + request.amount());
        }

        // f. Update balances in memory and persist both wallets
        sourceWallet.setBalance(sourceWallet.getBalance() - request.amount());
        destinationWallet.setBalance(destinationWallet.getBalance() + request.amount());
        walletRepository.save(sourceWallet);
        walletRepository.save(destinationWallet);

        // g. Create and save Transaction record (TRANSFER, COMPLETED)
        Transaction transaction = Transaction.builder()
                .referenceId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED)
                .amount(request.amount())
                .currency(sourceWallet.getCurrency())
                .sourceWallet(sourceWallet)
                .destinationWallet(destinationWallet)
                .description(request.description())
                .build();
        Transaction savedTx = transactionRepository.save(transaction);

        // h. Create and save two immutable LedgerEntry rows (DEBIT on source, CREDIT on destination)
        LedgerEntry debitEntry = LedgerEntry.builder()
                .transaction(savedTx)
                .wallet(sourceWallet)
                .entryType(EntryType.DEBIT)
                .amount(request.amount())
                .balanceAfter(sourceWallet.getBalance())
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .transaction(savedTx)
                .wallet(destinationWallet)
                .entryType(EntryType.CREDIT)
                .amount(request.amount())
                .balanceAfter(destinationWallet.getBalance())
                .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // Publish internal Spring application event to trigger post-commit Kafka dispatch
        eventPublisher.publishEvent(new TransactionCompletedEvent(
                UUID.randomUUID(),
                savedTx.getId(),
                savedTx.getReferenceId(),
                savedTx.getType(),
                savedTx.getAmount(),
                savedTx.getCurrency(),
                sourceWallet.getId(),
                destinationWallet.getId(),
                savedTx.getCreatedAt()
        ));

        // i. Return TransferResponse DTO
        recordTransferSuccess(request.amount());
        return new TransferResponse(
                savedTx.getId(),
                savedTx.getReferenceId(),
                savedTx.getStatus(),
                savedTx.getAmount(),
                savedTx.getCurrency(),
                sourceWallet.getId(),
                destinationWallet.getId(),
                sourceWallet.getBalance(),
                savedTx.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public DepositResponse findExistingDepositByTransaction(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(tx -> new DepositResponse(
                        tx.getId(),
                        tx.getReferenceId(),
                        tx.getStatus(),
                        tx.getAmount(),
                        tx.getCurrency(),
                        tx.getDestinationWallet().getId(),
                        tx.getDestinationWallet().getBalance(),
                        tx.getCreatedAt()
                )).orElse(null);
    }

    @Transactional(readOnly = true)
    public TransferResponse findExistingTransferByTransaction(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(tx -> new TransferResponse(
                        tx.getId(),
                        tx.getReferenceId(),
                        tx.getStatus(),
                        tx.getAmount(),
                        tx.getCurrency(),
                        tx.getSourceWallet().getId(),
                        tx.getDestinationWallet().getId(),
                        tx.getSourceWallet().getBalance(),
                        tx.getCreatedAt()
                )).orElse(null);
    }
}
