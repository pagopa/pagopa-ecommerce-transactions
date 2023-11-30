package it.pagopa.transactions.services.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.PaymentToken;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@TestPropertySource(
        locations = "classpath:application-tests.properties", properties = {
                "ecommerce.event.version=V1"
        }

)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerTest {
    @Autowired
    private TransactionsService transactionsService;

    @MockBean
    private TransactionActivateHandler transactionActivateHandlerV1;

    @MockBean
    private TransactionsViewRepository transactionsViewRepository;
    @MockBean
    private TransactionsUtils transactionsUtils;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    private static final JsonNode resilience4jConfiguration;

    private static final Map<String, Exception> exceptionMapper = Map.of(
            "it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException",
            new UnsatisfiablePspRequestException(
                    new PaymentToken(""),
                    RequestAuthorizationRequestDto.LanguageEnum.IT,
                    0
            ),
            "it.pagopa.transactions.exceptions.PaymentNoticeAllCCPMismatchException",
            new PaymentNoticeAllCCPMismatchException("rptId", true, true),
            "it.pagopa.transactions.exceptions.TransactionNotFoundException",
            new TransactionNotFoundException(""),
            "it.pagopa.transactions.exceptions.AlreadyProcessedException",
            new AlreadyProcessedException(new TransactionId(TransactionTestUtils.TRANSACTION_ID)),
            "it.pagopa.transactions.exceptions.NotImplementedException",
            new NotImplementedException(""),
            "it.pagopa.transactions.exceptions.InvalidRequestException",
            new InvalidRequestException(""),
            "it.pagopa.transactions.exceptions.TransactionAmountMismatchException",
            new TransactionAmountMismatchException(10, 11),
            "it.pagopa.transactions.exceptions.NodoErrorException",
            new NodoErrorException(new CtFaultBean()),
            "it.pagopa.transactions.exceptions.InvalidNodoResponseException",
            new InvalidNodoResponseException("")
    );

    static {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            resilience4jConfiguration = mapper.readTree(new File("./src/main/resources/application.yml"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Stream<Arguments> getIgnoredExceptionsForRetry(String retryInstanceName) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        resilience4jConfiguration
                                .get("resilience4j.retry")
                                .get("instances")
                                .get(retryInstanceName)
                                .get("ignoreExceptions")
                                .elements(),
                        Spliterator.ORDERED
                ),
                false
        )
                .map(json -> {
                    String exceptionName = json.asText();
                    return Arguments.of(
                            Optional
                                    .ofNullable(exceptionMapper.get(exceptionName))
                                    .orElseThrow(
                                            () -> new RuntimeException(
                                                    "Missing exception instance in test suite inside map `exceptionMapper` for class: %s".formatted(exceptionName)
                                            )
                                    ),
                            retryInstanceName
                    );
                }

                );
    }

    private static Stream<Arguments> getIgnoredExceptionForNewTransactionRetry() {
        return getIgnoredExceptionsForRetry("newTransaction");
    }

    private static Stream<Arguments> getIgnoredExceptionForGetTransactionInfoRetry() {
        return getIgnoredExceptionsForRetry("getTransactionInfo");
    }

    private static Stream<Arguments> getIgnoredExceptionForRequestTransactionAuthorizationRetry() {
        return getIgnoredExceptionsForRetry("requestTransactionAuthorization");
    }

    private static Stream<Arguments> getIgnoredExceptionForUpdateTransactionAuthorizationRetry() {
        return getIgnoredExceptionsForRetry("updateTransactionAuthorization");
    }

    private static Stream<Arguments> getIgnoredExceptionForCancelTransactionRetry() {
        return getIgnoredExceptionsForRetry("cancelTransaction");
    }

    private static Stream<Arguments> getIgnoredExceptionForAddUserReceiptRetry() {
        return getIgnoredExceptionsForRetry("addUserReceipt");
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForNewTransactionRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_newTransactionRetry(
                                                                       Exception thrownException,
                                                                       String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(10));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "desc",
                                        0,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandlerV1.handle(any())).thenReturn(Mono.error(thrownException));
        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForGetTransactionInfoRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_getTransactionInfoRetry(
                                                                           Exception thrownException,
                                                                           String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(any(String.class)))
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.getTransactionInfo("transactionId")
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForRequestTransactionAuthorizationRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_requestTransactionAuthorizationRetry(
                                                                                        Exception thrownException,
                                                                                        String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(any(String.class)))
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.requestTransactionAuthorization(
                                "transactionId",
                                "",
                                new RequestAuthorizationRequestDto()
                        )
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForUpdateTransactionAuthorizationRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_updateTransactionAuthorizationRetry(
                                                                                       Exception thrownException,
                                                                                       String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsUtils.reduceEvents(any(), any(), any(), any()))
                .thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService
                                .updateTransactionAuthorization(UUID.randomUUID(), new UpdateAuthorizationRequestDto())
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForCancelTransactionRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_cancelTransactionRetry(
                                                                          Exception thrownException,
                                                                          String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(any(String.class))).thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.cancelTransaction("")
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @ParameterizedTest
    @MethodSource("getIgnoredExceptionForAddUserReceiptRetry")
    @Order(0)
    void shouldNotPerformRetryForExcludedException_AddUserReceiptRetry(
                                                                       Exception thrownException,
                                                                       String retryInstanceName
    ) {
        Retry retry = retryRegistry.retry(retryInstanceName);
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + 1;

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(any(String.class))).thenReturn(Mono.error(thrownException));

        StepVerifier
                .create(
                        transactionsService.addUserReceipt("", new AddUserReceiptRequestDto())
                )
                .expectError(thrownException.getClass())
                .verify();
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
    }

    @Test
    @Order(1)
    void shouldNotOpenCircuitBreakerForNodoErrorException() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(10));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        /*
         * Preconditions
         */
        CtFaultBean ctFaultBean = faultBeanWithCode(
                PartyConfigurationFaultDto.PPT_STAZIONE_INT_PA_ERRORE_RESPONSE.getValue()
        );
        Mockito.when(transactionActivateHandlerV1.handle(any()))
                .thenReturn(Mono.error(new NodoErrorException(ctFaultBean)));

        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
                .expectError(NodoErrorException.class)
                .verify();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("node-backend");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

    }

    @Test
    @Order(2)
    void shouldOpenCircuitBreakerForNotExcludedExceptionPerformingRetry() {
        Retry retry = retryRegistry.retry("newTransaction");
        long expectedFailedCallsWithoutRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt();
        long expectedFailedCallsWithRetryAttempt = retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt() + 1;

        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_CCP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(10));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0,
                                        TEST_CCP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandlerV1.handle(any()))
                .thenReturn(Mono.error(new RuntimeException("Invalid response received")));

        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
                .expectError(CallNotPermittedException.class)
                .verify();
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("node-backend");
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(
                expectedFailedCallsWithoutRetryAttempt,
                retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        );
        assertEquals(expectedFailedCallsWithRetryAttempt, retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt());

    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
