package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class TransactionProjectionHandlerTest {

    @InjectMocks
    private TransactionsProjectionHandler transactionsProjectionHandler;

    @Mock
    private TransactionsViewRepository viewEventStoreRepository;

    @Test
    void shouldHandleTransaction() {

        UUID transactionUUID = UUID.randomUUID();

        NewTransactionResponseDto data = new NewTransactionResponseDto()
                .transactionId(transactionUUID.toString())
                .paymentToken("token")
                .rptId("77777777777302016723749670035")
                .reason("reason")
                .amount(1);

        TransactionInitEvent event = new TransactionInitEvent(
                data.getTransactionId(),
                data.getRptId(),
                data.getPaymentToken(),
                ZonedDateTime.now().toString(),
                new TransactionInitData(
                        data.getReason(),
                        data.getAmount(),
                        "foo@example.com",
                        "",
                        ""
                )
        );

        TransactionId transactionId = new TransactionId(transactionUUID);
        PaymentToken paymentToken = new PaymentToken(data.getPaymentToken());
        RptId rptId = new RptId(data.getRptId());
        TransactionDescription description = new TransactionDescription(data.getReason());
        TransactionAmount amount = new TransactionAmount(data.getAmount());
        Email email = new Email("foo@example.com");

        TransactionInitialized expected = new TransactionInitialized(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                TransactionStatusDto.INITIALIZED
        );

        try (
                MockedStatic<ZonedDateTime> zonedDateTime = Mockito.mockStatic(ZonedDateTime.class)) {
            /*
             * Preconditions
             */
            it.pagopa.transactions.documents.Transaction transactionDocument = it.pagopa.transactions.documents.Transaction
                    .from(expected);
            Mockito.when(viewEventStoreRepository.save(Mockito.any(it.pagopa.transactions.documents.Transaction.class)))
                    .thenReturn(Mono.just(transactionDocument));
            zonedDateTime.when(ZonedDateTime::now).thenReturn(expected.getCreationDate());

            /*
             * Test
             */
            TransactionInitialized result = transactionsProjectionHandler.handle(event).cast(TransactionInitialized.class).block();

            /*
             * Assertions
             */
            assertEquals(expected, result);
        }
    }
}
