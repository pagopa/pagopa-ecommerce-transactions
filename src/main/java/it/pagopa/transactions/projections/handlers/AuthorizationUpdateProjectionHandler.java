package it.pagopa.transactions.projections.handlers;

import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.UUID;

@Component
@Slf4j
public class AuthorizationUpdateProjectionHandler implements ProjectionHandler<TransactionAuthorizationStatusUpdatedEvent, Mono<TransactionInitialized>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<TransactionInitialized> handle(TransactionAuthorizationStatusUpdatedEvent data) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getPaymentToken())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(data.getData().getNewTransactionStatus());
                    return transactionsViewRepository.save(transactionDocument);
                })
                .map(transactionDocument -> new TransactionInitialized(
                        new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                        new PaymentToken(transactionDocument.getPaymentToken()),
                        new RptId(transactionDocument.getRptId()),
                        new TransactionDescription(transactionDocument.getDescription()),
                        new TransactionAmount(transactionDocument.getAmount()),
                        new Email(transactionDocument.getEmail()),
                        ZonedDateTime.parse(transactionDocument.getCreationDate()),
                        transactionDocument.getStatus()));
    }
}
