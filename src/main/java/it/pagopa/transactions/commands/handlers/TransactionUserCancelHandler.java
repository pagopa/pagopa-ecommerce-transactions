package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionExpired;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Slf4j
public class TransactionUserCancelHandler extends
        BaseHandler<TransactionUserCancelCommand, Mono<TransactionUserCanceledEvent>> {

    private final TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;
    private final QueueAsyncClient transactionClosureQueueAsyncClient;

    @Autowired
    public TransactionUserCancelHandler(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClient") QueueAsyncClient transactionClosureQueueAsyncClient
    ) {
        super(eventStoreRepository);
        this.transactionEventUserCancelStoreRepository = transactionEventUserCancelStoreRepository;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
    }

    @Override
    public Mono<TransactionUserCanceledEvent> handle(TransactionUserCancelCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(
                command.getData().value()
        );

        return transaction
                .cast(BaseTransaction.class)
                .onErrorMap(err -> new AlreadyProcessedException(command.getData()))
                .flatMap(

                        t -> {
                            TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                                    t.getTransactionId().value().toString()
                            );
                            return transactionEventUserCancelStoreRepository.save(userCanceledEvent)
                                    .then(
                                            transactionClosureQueueAsyncClient.sendMessageWithResponse(
                                                    BinaryData.fromObject(userCanceledEvent),
                                                    Duration.ZERO,
                                                    null
                                            )
                                    )
                                    .then(Mono.just(userCanceledEvent))
                                    .doOnError(
                                            exception -> log.error(
                                                    "Error to generate event TRANSACTION_USER_CANCELED_EVENT for transactionId {} - error {}",
                                                    userCanceledEvent.getTransactionId(),
                                                    exception.getMessage()
                                            )
                                    )
                                    .doOnNext(
                                            event -> log.info(
                                                    "Generated event TRANSACTION_USER_CANCELED_EVENT for transactionId {}",
                                                    event.getTransactionId()
                                            )
                                    );
                        }
                );

    }
}
