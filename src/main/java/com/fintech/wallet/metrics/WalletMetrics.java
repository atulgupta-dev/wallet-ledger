package com.fintech.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

@Component
public class WalletMetrics {

    private final Counter transferSuccessCounter;
    private final Counter transferInsufficientFundsCounter;
    private final Counter transferErrorCounter;
    private final Counter depositSuccessCounter;
    private final Timer transferTimer;
    private final Timer depositTimer;
    private final DistributionSummary transferAmountSummary;

    public WalletMetrics(MeterRegistry meterRegistry) {
        this.transferSuccessCounter = Counter.builder("wallet.transfers.count")
                .tag("status", "success")
                .tag("type", "transfer")
                .description("Total successful peer-to-peer wallet transfers")
                .register(meterRegistry);

        this.transferInsufficientFundsCounter = Counter.builder("wallet.transfers.count")
                .tag("status", "insufficient_funds")
                .tag("type", "transfer")
                .description("Transfers rejected due to insufficient funds")
                .register(meterRegistry);

        this.transferErrorCounter = Counter.builder("wallet.transfers.count")
                .tag("status", "error")
                .tag("type", "transfer")
                .description("Transfers failed due to unexpected errors")
                .register(meterRegistry);

        this.depositSuccessCounter = Counter.builder("wallet.transfers.count")
                .tag("status", "success")
                .tag("type", "deposit")
                .description("Total successful wallet deposits")
                .register(meterRegistry);

        this.transferTimer = Timer.builder("wallet.transfers.duration")
                .tag("type", "transfer")
                .description("Latency distribution of wallet transfers")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.depositTimer = Timer.builder("wallet.transfers.duration")
                .tag("type", "deposit")
                .description("Latency distribution of wallet deposits")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.transferAmountSummary = DistributionSummary.builder("wallet.transfers.amount")
                .description("Transfer monetary amount distribution")
                .baseUnit("paise")
                .register(meterRegistry);
    }

    public <T> T recordTransferTime(Callable<T> callable) throws Exception {
        return transferTimer.recordCallable(callable);
    }

    public <T> T recordDepositTime(Callable<T> callable) throws Exception {
        return depositTimer.recordCallable(callable);
    }

    public void incrementTransferSuccess(Long amount) {
        transferSuccessCounter.increment();
        if (amount != null && amount > 0) {
            transferAmountSummary.record(amount);
        }
    }

    public void incrementInsufficientFunds() {
        transferInsufficientFundsCounter.increment();
    }

    public void incrementTransferError() {
        transferErrorCounter.increment();
    }

    public void incrementDepositSuccess() {
        depositSuccessCounter.increment();
    }
}
