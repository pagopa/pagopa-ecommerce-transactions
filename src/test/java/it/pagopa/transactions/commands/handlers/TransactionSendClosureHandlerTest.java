package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.ecommerce.commons.documents.v1.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData.PaymentGateway;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureFailedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionRefundedData;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.PaymentContextCode;
import it.pagopa.ecommerce.commons.domain.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v1.PaymentToken;
import it.pagopa.ecommerce.commons.domain.v1.PaymentTransferInfo;
import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionAmount;
import it.pagopa.ecommerce.commons.domain.v1.TransactionDescription;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.EuroUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static it.pagopa.transactions.commands.handlers.TransactionSendClosureHandler.ECOMMERCE_RRN;
import static it.pagopa.transactions.commands.handlers.TransactionSendClosureHandler.TIPO_VERSAMENTO_CP;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

@ExtendWith(MockitoExtension.class)
class TransactionSendClosureHandlerTest {
    private final TransactionsEventStoreRepository<TransactionClosureData> transactionEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsEventStoreRepository<TransactionRefundedData> transactionRefundedEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final PaymentRequestsInfoRepository paymentRequestsInfoRepositoryRepository = Mockito
            .mock(PaymentRequestsInfoRepository.class);

    private final TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository);
    private final NodeForPspClient nodeForPspClient = Mockito.mock(NodeForPspClient.class);

    private final QueueAsyncClient transactionClosureSentEventQueueClient = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient refundQueueAsyncClient = Mockito.mock(QueueAsyncClient.class);

    private static final int PAYMENT_TOKEN_VALIDITY = 120;
    private static final int SOFT_TIMEOUT_OFFSET = 10;
    private static final int RETRY_TIMEOUT_INTERVAL = 5;

    private final TransactionSendClosureHandler transactionSendClosureHandler = new TransactionSendClosureHandler(
            transactionEventStoreRepository,
            transactionClosureErrorEventStoreRepository,
            transactionRefundedEventStoreRepository,
            paymentRequestsInfoRepositoryRepository,
            nodeForPspClient,
            transactionClosureSentEventQueueClient,
            PAYMENT_TOKEN_VALIDITY,
            SOFT_TIMEOUT_OFFSET,
            RETRY_TIMEOUT_INTERVAL,
            refundQueueAsyncClient,
            transactionsUtils
    );

    private final TransactionId transactionId = new TransactionId(UUID.fromString(TransactionTestUtils.TRANSACTION_ID));

    private static MockedStatic<OffsetDateTime> offsetDateTimeMockedStatic;

    @BeforeAll
    static void init() {
        offsetDateTimeMockedStatic = Mockito.mockStatic(OffsetDateTime.class);
        offsetDateTimeMockedStatic.when(OffsetDateTime::now).thenReturn(OffsetDateTime.MIN);
    }

    @AfterAll
    static void shutdown() {
        offsetDateTimeMockedStatic.close();
    }

    @Test
    void shouldRejectTransactionInWrongState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");

        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation("77777777777", false, 100, null))
                )
        );

        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                PaymentNotices.stream().map(
                        paymentNotice -> new PaymentNotice(
                                new PaymentToken(paymentNotice.getPaymentToken()),
                                new RptId(paymentNotice.getRptId()),
                                new TransactionAmount(paymentNotice.getAmount()),
                                new TransactionDescription(paymentNotice.getDescription()),
                                new PaymentContextCode(paymentNotice.getPaymentContextCode()),
                                List.of(
                                        new PaymentTransferInfo(
                                                paymentNotice.getRptId().substring(0, 11),
                                                false,
                                                100,
                                                null
                                        )
                                )
                        )
                )
                        .toList(),
                email,
                faultCode,
                faultCodeString,
                Transaction.ClientId.CHECKOUT,
                idCart
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        transaction.getTransactionActivatedData().getPaymentNotices(),
                        faultCode,
                        faultCodeString,
                        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.VPOS
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        TransactionClosedEvent closedEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        Flux events = Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closedEvent
        );

        Mockito.when(eventStoreRepository.findByTransactionId(any())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToClosureFailedOnNodoKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.XPAY
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shoulGenerateClosureFailedEventOnAuthorizationKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.XPAY
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(),
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shoulGenerateClosedEventOnAuthorizationOKAndOkNodoClosePayment() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation("77777777777", false, 100, null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.VPOS
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }

    @Test
    void shouldEnqueueErrorEventOnGenericNodoFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.XPAY
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(),
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new RuntimeException("Network error");

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldEnqueueErrorEventOnRedisFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.VPOS
                )
        );

        TransactionAuthorizationCompletedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getAmount() + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(),
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException redisError = new RuntimeException("Network error");

        /* preconditions */
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        // first call to redis is ko, second one is ok
        Mockito.when(transactionEventStoreRepository.save(any()))
                .thenReturn(Mono.error(redisError));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));

        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoUnrecoverableFailureTransactionAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.XPAY
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );
        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())

        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                Mockito.times(1)
        ).sendMessageWithResponse(
                argThat(
                        (BinaryData b) -> b.toObject(TransactionRefundRequestedEvent.class).getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        // check that one closure error event is saved and none is sent to event
        // dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoUnrecoverableFailureTransactionNotAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.VPOS
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );
        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                Mockito.times(1)
        ).sendMessageWithResponse(
                argThat(
                        (BinaryData b) -> b.toObject(TransactionRefundRequestedEvent.class).getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        // check that no closure error event is saved but not sent to event dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
        // check that closure error event is saved
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldNotEnqueueErrorEventOnNodoRecoverableFailureTransactionNotAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.XPAY
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.KO
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );
        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new BadGatewayException(
                "Internal server error",
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        // check that no closure error event is saved and sent to event dispatcher
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
        // check that closure event with KO status is not saved
        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                                && eventArg.getData().getResponseOutcome().equals(TransactionClosureData.Outcome.KO)
                )
        );
    }

    @Test
    void shouldEnqueueErrorEventOnNodoRecoverableFailureTransactionAuthorized() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> PaymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        PaymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.VPOS
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );
        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new BadGatewayException(
                "Internal server error",
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldEnqueueErrorEventOnNodoRecoverableFailure() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        List<it.pagopa.ecommerce.commons.documents.v1.PaymentNotice> paymentNotices = List.of(
                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                        paymentToken.value(),
                        rptId.value(),
                        description.value(),
                        amount.value(),
                        null,
                        List.of(new PaymentTransferInformation(rptId.getFiscalCode(), false, amount.value(), null))
                )
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        paymentNotices,
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT,
                        idCart
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId",
                        PaymentGateway.XPAY
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        transactionActivatedEvent.getData().getPaymentNotices().stream()
                                .map(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getPaymentToken).toList()
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                (transactionActivatedEvent.getData().getPaymentNotices().stream()
                                        .mapToInt(it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount)
                                        .sum()
                                        + authorizationRequestData.getFee())
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new BadGatewayException(
                "Internal server error",
                HttpStatus.INTERNAL_SERVER_ERROR
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any())).thenReturn(Mono.just(errorEvent));
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        Hooks.onOperatorDebug();

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1))
                .save(argThat(e -> e.getEventCode().equals(TransactionEventCode.TRANSACTION_CLOSURE_ERROR_EVENT)));
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> b.toByteBuffer()
                                        .equals(BinaryData.fromObject(errorEvent).toByteBuffer())
                        ),
                        argThat(d -> d.compareTo(Duration.ofSeconds(RETRY_TIMEOUT_INTERVAL)) <= 0),
                        isNull()
                );
    }

    @Test
    void shouldSendRefundRequestEventOnQueueForAuthorizedTransactionAndNodoClosePaymentResponseOutcomeKO() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())

        ).thenReturn(queueSuccessfulResponse());

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(refundQueueAsyncClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> {
                                    TransactionRefundRequestedEvent e = b
                                            .toObject(TransactionRefundRequestedEvent.class);
                                    return e.getTransactionId().equals(transactionId.value().toString()) && e.getData()
                                            .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSED);
                                }

                        ),
                        eq(Duration.ZERO),
                        isNull()
                );
    }

    @Test
    void shouldSendRefundRequestedEventOnQueueForAuthorizedTransactionAndNodoClosePaymentUnrecoverableError() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        /*
         * check that the closure event with outcome KO is sent in the transaction
         * activated queue
         */
        Mockito.verify(refundQueueAsyncClient, Mockito.times(1))
                .sendMessageWithResponse(
                        argThat(
                                (BinaryData b) -> {
                                    TransactionRefundRequestedEvent e = b
                                            .toObject(TransactionRefundRequestedEvent.class);
                                    return e.getTransactionId().equals(transactionId.value().toString()) && e.getData()
                                            .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR);
                                }
                        ),
                        eq(Duration.ZERO),
                        isNull()
                );

        /*
         * check that no event is sent on the closure error queue
         */
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        isNull()
                );
    }

    @Test
    void shouldNotSendClosedEventOnQueueForNotAuthorizedTransactionAndNodoClosePaymentResponseOutcomeKO() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.KO);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.KO);

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(), // 2023-04-03T15:42:22.826Z
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        ClosePaymentResponseDto closePaymentResponse = new ClosePaymentResponseDto()
                .outcome(ClosePaymentResponseDto.OutcomeEnum.KO);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.just(closePaymentResponse));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(
                transactionClosureSentEventQueueClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isRight());
                    assertNotNull(next.get());
                    assertEquals(event.getData().getResponseOutcome(), next.get().getData().getResponseOutcome());
                    assertEquals(event.getEventCode(), next.get().getEventCode());
                    assertEquals(event.getTransactionId(), next.get().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_CLOSURE_FAILED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                );
    }

    @Test
    void shouldNotSendClosedEventOnQueueForNotAuthorizedTransactionAndNodoClosePaymentUnrecoverableError() {

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(
                        AuthorizationResultDto.KO
                );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        it.pagopa.ecommerce.commons.domain.v1.Transaction transaction = events
                .reduce(new EmptyTransaction(), it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent).block();

        ClosureSendData closureSendData = new ClosureSendData(
                (BaseTransactionWithPaymentToken) transaction,
                updateAuthorizationRequest
        );

        TransactionClosureSendCommand closureSendCommand = new TransactionClosureSendCommand(
                new RptId(transactionActivatedEvent.getData().getPaymentNotices().get(0).getRptId()),
                closureSendData
        );

        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(
                        List.of(
                                ((BaseTransactionWithPaymentToken) transaction).getTransactionActivatedData()
                                        .getPaymentNotices().get(0).getPaymentToken()
                        )
                )
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.KO)
                .idPSP(authorizationRequestData.getPspId())
                .idBrokerPSP(authorizationRequestData.getBrokerName())
                .idChannel(authorizationRequestData.getPspChannelCode())
                .transactionId(((BaseTransactionWithPaymentToken) transaction).getTransactionId().value().toString())
                .totalAmount(
                        EuroUtils.euroCentsToEuro(
                                ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationRequestData.getFee()
                        )
                )
                .fee(EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()))
                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                .additionalPaymentInformations(
                        Map.of(
                                "outcome_payment_gateway",
                                updateAuthorizationRequest.getAuthorizationResult().toString(),
                                "authorization_code",
                                updateAuthorizationRequest.getAuthorizationCode(),
                                "tipoVersamento",
                                TIPO_VERSAMENTO_CP,
                                "rrn",
                                ECOMMERCE_RRN,
                                "fee",
                                EuroUtils.euroCentsToEuro(authorizationRequestData.getFee()).toString(),
                                "timestampOperation",
                                OffsetDateTime.now().toString(),
                                "totalAmount",
                                EuroUtils.euroCentsToEuro(
                                        ((BaseTransactionWithPaymentToken) transaction).getPaymentNotices().stream()
                                                .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value())
                                                .sum()
                                                + authorizationRequestData.getFee()
                                ).toString()
                        )
                );

        TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                transactionId.value().toString()
        );

        RuntimeException closePaymentError = new BadGatewayException("Bad request error", HttpStatus.BAD_REQUEST);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(nodeForPspClient.closePaymentV2(closePaymentRequest)).thenReturn(Mono.error(closePaymentError));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(
                refundQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any())
        ).thenReturn(queueSuccessfulResponse());
        Mockito.when(transactionRefundedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(transactionClosureErrorEventStoreRepository.save(any()))
                .thenAnswer(a -> Mono.just(a.getArgument(0)));

        /* test */
        StepVerifier.create(transactionSendClosureHandler.handle(closureSendCommand))
                .consumeNextWith(next -> {
                    assertTrue(next.isLeft());
                    assertNotNull(next.getLeft());
                    assertEquals(errorEvent.getEventCode(), next.getLeft().getEventCode());
                    assertEquals(errorEvent.getTransactionId(), next.getLeft().getTransactionId());
                })
                .verifyComplete();

        Mockito.verify(
                refundQueueAsyncClient,
                Mockito.times(1)
        ).sendMessageWithResponse(
                argThat(
                        (BinaryData b) -> b.toObject(TransactionRefundRequestedEvent.class).getData()
                                .getStatusBeforeRefunded().equals(TransactionStatusDto.CLOSURE_ERROR)
                ),
                any(),
                any()
        );
        Mockito.verify(transactionClosureErrorEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionClosureSentEventQueueClient, Mockito.times(0))
                .sendMessageWithResponse(
                        any(BinaryData.class),
                        any(),
                        any()
                );
    }

    private static Mono<Response<SendMessageResult>> queueSuccessfulResponse() {
        return Mono.just(new Response<>() {
            @Override
            public int getStatusCode() {
                return 200;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public HttpRequest getRequest() {
                return null;
            }

            @Override
            public SendMessageResult getValue() {
                return new SendMessageResult();
            }
        });
    }
}
