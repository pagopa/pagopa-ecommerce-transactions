package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionAddReceiptData;
import it.pagopa.ecommerce.commons.documents.TransactionUserReceiptAddedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUserReceiptProjectionHandlerTest {

    @InjectMocks
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @Mock
    private TransactionsViewRepository viewRepository;

    @Test
    void shouldHandleTransaction() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("OK")
                .timestampOperation(OffsetDateTime.now());

        String faultCode = null;
        String faultCodeString = null; // FIXME, make handle pass fault codes correctly

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(UUID.randomUUID()),
                new PaymentToken("paymentToken"),
                new RptId("77777777777111111111111111111"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                new Email("foo@example.com"),
                faultCode,
                faultCodeString,
                TransactionStatusDto.CLOSED
        );

        it.pagopa.ecommerce.commons.documents.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getTransactionActivatedData().getPaymentToken(),
                transaction.getRptId().value(),
                transaction.getDescription().value(),
                transaction.getAmount().value(),
                transaction.getEmail().value(),
                TransactionStatusDto.NOTIFIED,
                transaction.getCreationDate()
        );

        TransactionAddReceiptData transactionAddReceiptData = new TransactionAddReceiptData(expectedDocument.getStatus());

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transaction.getTransactionId().value().toString(),
                transaction.getRptId().value(),
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAddReceiptData
        );

        TransactionActivated expected = new TransactionActivated(
                transaction.getTransactionId(),
                new PaymentToken(transaction.getTransactionActivatedData().getPaymentToken()),
                transaction.getRptId(),
                transaction.getDescription(),
                transaction.getAmount(),
                transaction.getEmail(),
                transaction.getTransactionActivatedData().getFaultCode(),
                transaction.getTransactionActivatedData().getFaultCodeString(),
                ZonedDateTime.parse(expectedDocument.getCreationDate()),
                expectedDocument.getStatus()
        );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1)).save(argThat(savedTransaction -> savedTransaction.getStatus().equals(TransactionStatusDto.NOTIFIED)));
    }
}
