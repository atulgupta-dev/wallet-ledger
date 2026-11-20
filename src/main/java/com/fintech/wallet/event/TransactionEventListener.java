package com.fintech.wallet.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransactionEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventListener.class);
    public static final String TOPIC = "wallet.transactions.completed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TransactionEventListener(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        // Partition by sourceWalletId (or destinationWalletId for deposits) to guarantee per-wallet order
        String partitionKey = event.sourceWalletId() != null
                ? event.sourceWalletId().toString()
                : (event.destinationWalletId() != null ? event.destinationWalletId().toString() : "wallet-event");

        try {
            kafkaTemplate.send(TOPIC, partitionKey, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Broker send error for TransactionCompletedEvent [eventId={}, txId={}]: {}",
                            event.eventId(), event.transactionId(), ex.getMessage(), ex);
                } else {
                    log.info("Dispatched TransactionCompletedEvent [eventId={}, txId={}] to Kafka topic '{}' (partition: {}, offset: {})",
                            event.eventId(), event.transactionId(), TOPIC,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (Exception ex) {
            log.error("Unexpected exception dispatching TransactionCompletedEvent [eventId={}, txId={}]: {}",
                    event.eventId(), event.transactionId(), ex.getMessage(), ex);
        }
    }
}
