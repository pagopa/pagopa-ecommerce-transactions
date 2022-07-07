package it.pagopa.transactions.repositories;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import it.pagopa.transactions.documents.Transaction;
import reactor.core.publisher.Mono;

public interface TransactionsViewRepository extends ReactiveCrudRepository<Transaction, String> {
    Mono<Transaction> findByPaymentToken(String paymentToken);
    Mono<Transaction> findByTransactionId(String transactionId);
}
