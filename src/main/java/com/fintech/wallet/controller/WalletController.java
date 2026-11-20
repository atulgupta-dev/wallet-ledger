package com.fintech.wallet.controller;

import com.fintech.wallet.dto.DepositRequest;
import com.fintech.wallet.dto.DepositResponse;
import com.fintech.wallet.dto.TransferRequest;
import com.fintech.wallet.dto.TransferResponse;
import com.fintech.wallet.dto.WalletResponse;
import com.fintech.wallet.exception.InvalidTransferException;
import com.fintech.wallet.security.UserPrincipal;
import com.fintech.wallet.service.IdempotencyResult;
import com.fintech.wallet.service.IdempotencyService;
import com.fintech.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets", description = "Wallet balance and account operations")
@SecurityRequirement(name = "BearerAuth")
public class WalletController {

    private final WalletService walletService;
    private final IdempotencyService idempotencyService;

    public WalletController(WalletService walletService, IdempotencyService idempotencyService) {
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user's wallet", description = "Retrieves wallet details and balance for the authenticated user")
    public ResponseEntity<WalletResponse> getMyWallet(@AuthenticationPrincipal UserPrincipal principal) {
        WalletResponse wallet = walletService.getWalletByUserId(principal.getId());
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/me/transactions")
    @Operation(summary = "Get ledger and transaction history", description = "Retrieves paginated ledger entries for the authenticated user's wallet ordered by createdAt DESC")
    public ResponseEntity<com.fintech.wallet.dto.PageResponse<com.fintech.wallet.dto.LedgerEntryResponse>> getMyTransactions(
            @AuthenticationPrincipal UserPrincipal principal,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) com.fintech.wallet.entity.EntryType entryType,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant from,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant to) {

        com.fintech.wallet.dto.PageResponse<com.fintech.wallet.dto.LedgerEntryResponse> history =
                walletService.getLedgerHistory(principal.getId(), page, size, entryType, from, to);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/deposit")
    @Operation(
            summary = "Deposit funds",
            description = "Deposits funds into the authenticated user's wallet with Redis idempotency and pessimistic locking"
    )
    public ResponseEntity<DepositResponse> deposit(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Unique operation key (max 128 chars)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody DepositRequest request) {

        if (!StringUtils.hasText(idempotencyKeyHeader)) {
            throw new InvalidTransferException("Idempotency-Key HTTP header is required");
        }

        String requestHash = idempotencyService.computePayloadHash(request.amount(), null, request.description());
        IdempotencyResult<DepositResponse> result = idempotencyService.execute(
                principal.getId(),
                idempotencyKeyHeader,
                requestHash,
                DepositResponse.class,
                () -> walletService.deposit(principal.getId(), request, idempotencyKeyHeader.trim()),
                () -> walletService.findExistingDepositByTransaction(idempotencyKeyHeader.trim())
        );

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        if (result.isReplay()) {
            responseBuilder.header("X-Idempotent-Replay", "true");
        }
        return responseBuilder.body(result.response());
    }

    @PostMapping("/transfer")
    @Operation(
            summary = "Transfer funds",
            description = "Executes peer-to-peer double-entry transfer with deadlock prevention and Redis idempotency"
    )
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, description = "Unique operation key (max 128 chars)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody TransferRequest request) {

        if (!StringUtils.hasText(idempotencyKeyHeader)) {
            throw new InvalidTransferException("Idempotency-Key HTTP header is required");
        }

        String requestHash = idempotencyService.computePayloadHash(request.amount(), request.destinationWalletId(), request.description());
        IdempotencyResult<TransferResponse> result = idempotencyService.execute(
                principal.getId(),
                idempotencyKeyHeader,
                requestHash,
                TransferResponse.class,
                () -> walletService.transfer(principal.getId(), request, idempotencyKeyHeader.trim()),
                () -> walletService.findExistingTransferByTransaction(idempotencyKeyHeader.trim())
        );

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        if (result.isReplay()) {
            responseBuilder.header("X-Idempotent-Replay", "true");
        }
        return responseBuilder.body(result.response());
    }
}
