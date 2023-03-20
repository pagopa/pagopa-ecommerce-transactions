package it.pagopa.transactions.commands.handlers;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.PostePayAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionRequestAuthorizizationHandlerTest {

    private TransactionRequestAuthorizationHandler requestAuthorizationHandler;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository);

    private final UUID transactionIdUUID = UUID.randomUUID();

    TransactionId transactionId = new TransactionId(UUID.fromString(TransactionTestUtils.TRANSACTION_ID));

    @BeforeEach
    private void init() {
        requestAuthorizationHandler = new TransactionRequestAuthorizationHandler(
                paymentGatewayClient,
                transactionEventStoreRepository,
                transactionsUtils
        );
    }

    @Test
    void shouldSaveAuthorizationEvent() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        PostePayAuthResponseEntityDto postePayAuthResponseEntityDto = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.just(postePayAuthResponseEntityDto));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldSaveAuthorizationEventXPAY() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "XPAY",
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        XPayAuthResponseEntityDto xPayAuthResponseEntityDto = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.just(xPayAuthResponseEntityDto));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldRejectAlreadyProcessedTransaction() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                null,
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(
                (Flux) Flux.just(
                        TransactionTestUtils.transactionActivateEvent(),
                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                )
        );

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectBadGateway() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(new PaymentNotice(paymentToken, rptId, amount, description, nullPaymentContextCode)),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("VPOS")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction,
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "pspBusinessName",
                "GPAY",
                new PostePayAuthRequestDetailsDto().detailType("GPAY").accountEmail("test@test.it")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData)).thenReturn(Mono.empty());
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadRequestException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
