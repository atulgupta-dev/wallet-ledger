package com.fintech.wallet.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionNotificationConsumer.class);

    @KafkaListener(topics = "wallet.transactions.completed", groupId = "notification-service")
    public void consume(TransactionCompletedEvent event) {
        log.info("[notification-service] Received TransactionCompletedEvent: type={}, amount={}, currency={}, txId={}, refId={}, sourceWalletId={}, destWalletId={}",
                event.type(), event.amount(), event.currency(), event.transactionId(), event.referenceId(),
                event.sourceWalletId(), event.destinationWalletId());
    }
}
