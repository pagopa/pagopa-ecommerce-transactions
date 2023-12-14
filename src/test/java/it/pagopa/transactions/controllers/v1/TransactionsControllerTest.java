package it.pagopa.transactions.controllers.v1;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.domain.Claims;
import it.pagopa.ecommerce.commons.domain.PaymentToken;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JWTTokenGenerationException;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.model.CtFaultBean;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v1.TransactionsService;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
@WebFluxTest(TransactionsController.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
@AutoConfigureDataRedis
class TransactionsControllerTest {

    @InjectMocks
    private TransactionsController transactionsController = new TransactionsController();

    @MockBean
    @Qualifier(TransactionsService.QUALIFIER_NAME)
    private TransactionsService transactionsService;

    @MockBean
    private JwtTokenUtils jwtTokenUtils;

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private TransactionsUtils transactionsUtils;

    @MockBean
    private UUIDUtils uuidUtils;

    @MockBean
    private UniqueIdUtils uniqueIdUtils;

    @Mock
    ServerWebExchange mockExchange;

    @Mock
    ServerHttpRequest mockRequest;

    @Mock
    HttpHeaders mockHeaders;

    @Test
    void shouldGetOk() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        try (MockedStatic<UUID> uuidMockedStatic = Mockito.mockStatic(UUID.class)) {
            uuidMockedStatic.when(UUID::randomUUID).thenReturn(transactionId.uuid());
            String RPTID = "77777777777302016723749670035";
            String EMAIL = "mario.rossi@email.com";
            ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
            NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto();
            newTransactionRequestDto.addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(RPTID));
            newTransactionRequestDto.setEmail(EMAIL);

            NewTransactionResponseDto response = new NewTransactionResponseDto();

            PaymentInfoDto paymentInfoDto = new PaymentInfoDto();
            paymentInfoDto.setAmount(10);
            paymentInfoDto.setReason("Reason");
            paymentInfoDto.setPaymentToken("payment_token");
            paymentInfoDto.setRptId(RPTID);

            response.addPaymentsItem(paymentInfoDto);
            response.setAuthToken("token");
            Mockito.when(
                    jwtTokenUtils.generateToken(
                            any(SecretKey.class),
                            anyInt(),
                            eq(new Claims(transactionId, "orderId", null))
                    )
            ).thenReturn(Either.right(""));
            Mockito.lenient()
                    .when(
                            transactionsService
                                    .newTransaction(
                                            newTransactionRequestDto,
                                            clientIdDto,
                                            transactionId
                                    )
                    )
                    .thenReturn(Mono.just(response));

            ResponseEntity<NewTransactionResponseDto> responseEntity = transactionsController
                    .newTransaction(clientIdDto, Mono.just(newTransactionRequestDto), null).block();

            // Verify mock
            Mockito.verify(transactionsService, Mockito.times(1))
                    .newTransaction(newTransactionRequestDto, clientIdDto, transactionId);

            // Verify status code and response
            assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
            assertEquals(response, responseEntity.getBody());
        }
    }

    @Test
    void shouldGetTransactionInfoGetPaymentToken() {

        TransactionInfoDto response = new TransactionInfoDto();
        PaymentInfoDto paymentInfoDto = new PaymentInfoDto();
        paymentInfoDto.setAmount(10);
        paymentInfoDto.setReason("Reason");
        paymentInfoDto.setPaymentToken("payment_token");
        response.addPaymentsItem(paymentInfoDto);
        response.setAuthToken("token");

        String transactionId = new TransactionId(UUID.randomUUID()).value();

        Mockito.lenient().when(transactionsService.getTransactionInfo(transactionId)).thenReturn(Mono.just(response));

        ResponseEntity<TransactionInfoDto> responseEntity = transactionsController
                .getTransactionInfo(transactionId, null).block();

        // Verify mock
        Mockito.verify(transactionsService, Mockito.times(1)).getTransactionInfo(transactionId);

        // Verify status code and response
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(response, responseEntity.getBody());
    }

    @Test
    void shouldCancelTransactionInfo() {

        String transactionId = new TransactionId(UUID.randomUUID()).value();
        Mockito.lenient().when(transactionsService.cancelTransaction(transactionId)).thenReturn(Mono.empty());

        ResponseEntity<Void> responseEntity = transactionsController
                .requestTransactionUserCancellation(transactionId, null).block();

        // Verify mock
        Mockito.verify(transactionsService, Mockito.times(1)).cancelTransaction(transactionId);

        // Verify status code and response
        assertEquals(HttpStatus.ACCEPTED, responseEntity.getStatusCode());
    }

    @Test
    void shouldReturnTransactionNotFoundForCancelTransactionInfo() {

        String transactionId = new TransactionId(UUID.randomUUID()).value();
        /* test */
        Mockito.when(transactionsService.cancelTransaction(transactionId))
                .thenReturn(Mono.error(new TransactionNotFoundException(transactionId)));
        StepVerifier.create(
                transactionsController
                        .requestTransactionUserCancellation(transactionId, null)
        )
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldRedirectToAuthorizationURIForValidRequest() throws URISyntaxException {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("pspId");

        RequestAuthorizationResponseDto authorizationResponse = new RequestAuthorizationResponseDto()
                .authorizationUrl(new URI("https://example.com").toString());
        String pgsId = "XPAY";

        /* preconditions */
        Mockito.when(transactionsService.requestTransactionAuthorization(transactionId, pgsId, authorizationRequest))
                .thenReturn(Mono.just(authorizationResponse));

        /* test */
        ResponseEntity<RequestAuthorizationResponseDto> response = transactionsController
                .requestTransactionAuthorization(transactionId, Mono.just(authorizationRequest), pgsId, mockExchange)
                .block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(authorizationResponse, response.getBody());
    }

    @Test
    void shouldReturnNotFoundForNonExistingRequest() {
        String transactionId = new TransactionId(UUID.randomUUID()).value();
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(1)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("pspId");

        String pgsId = "XPAY";

        /* preconditions */
        Mockito.when(transactionsService.requestTransactionAuthorization(transactionId, pgsId, authorizationRequest))
                .thenReturn(Mono.error(new TransactionNotFoundException(transactionId)));

        /* test */
        Mono<ResponseEntity<RequestAuthorizationResponseDto>> mono = transactionsController
                .requestTransactionAuthorization(transactionId, Mono.just(authorizationRequest), pgsId, mockExchange);
        assertThrows(
                TransactionNotFoundException.class,
                () -> mono.block()
        );
    }

    @Test
    void shouldReturnTransactionInfoOnCorrectAuthorizationAndClosure() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String paymentToken = "paymentToken";
        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(100)
                                .paymentToken(paymentToken)
                )
                .authToken("authToken")
                .status(TransactionStatusDto.AUTHORIZATION_COMPLETED);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                ).timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(
                transactionsService.updateTransactionAuthorization(transactionId.uuid(), updateAuthorizationRequest)
        )
                .thenReturn(Mono.just(transactionInfo));
        Mockito.when(uuidUtils.uuidFromBase64(transactionId.value())).thenReturn(Either.right(transactionId.uuid()));
        Hooks.onOperatorDebug();
        /* test */
        ResponseEntity<TransactionInfoDto> response = transactionsController
                .updateTransactionAuthorization(transactionId.value(), Mono.just(updateAuthorizationRequest), null)
                .block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(transactionInfo, response.getBody());
    }

    @Test
    void shouldReturnNotFoundForAuthorizingNonExistingRequest() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                ).timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(
                transactionsService.updateTransactionAuthorization(transactionId.uuid(), updateAuthorizationRequest)
        )
                .thenReturn(Mono.error(new TransactionNotFoundException(transactionId.value())));
        Mockito.when(uuidUtils.uuidFromBase64(transactionId.value())).thenReturn(Either.right(transactionId.uuid()));
        /* test */
        StepVerifier.create(
                transactionsController
                        .updateTransactionAuthorization(
                                transactionId.value(),
                                Mono.just(updateAuthorizationRequest),
                                null
                        )
        )
                .expectErrorMatches(error -> error instanceof TransactionNotFoundException)
                .verify();
    }

    @Test
    void shouldReturnBadGatewayOnNodoHttpError() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                ).timestampOperation(OffsetDateTime.now());

        /* preconditions */
        Mockito.when(
                transactionsService.updateTransactionAuthorization(transactionId.uuid(), updateAuthorizationRequest)
        )
                .thenReturn(Mono.error(new BadGatewayException("", HttpStatus.BAD_REQUEST)));

        Mockito.when(uuidUtils.uuidFromBase64(transactionId.value())).thenReturn(Either.right(transactionId.uuid()));
        /* test */

        StepVerifier.create(
                transactionsController
                        .updateTransactionAuthorization(
                                transactionId.value(),
                                Mono.just(updateAuthorizationRequest),
                                null
                        )
        )
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
    }

    @Test
    void testTransactionNotFoundExceptionHandler() {
        final String PAYMENT_TOKEN = "aaa";

        ResponseEntity<ProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Transaction not found")
                        .detail("Transaction for payment token not found"),
                HttpStatus.NOT_FOUND
        );
        TransactionNotFoundException exception = new TransactionNotFoundException(PAYMENT_TOKEN);

        ResponseEntity<ProblemJsonDto> response = transactionsController.transactionNotFoundHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testAlreadyProcessedTransactionExceptionHandler() {
        final TransactionId transactionId = new TransactionId(UUID.randomUUID());

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already processed")
                        .detail("Transaction for RPT id '' has been already processed"),
                HttpStatus.CONFLICT
        );
        AlreadyProcessedException exception = new AlreadyProcessedException(transactionId);

        ResponseEntity<ProblemJsonDto> response = transactionsController.alreadyProcessedHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testUnsatisfiablePspRequestExceptionHandler() {
        final PaymentToken PAYMENT_TOKEN = new PaymentToken("aaa");
        final RequestAuthorizationRequestDto.LanguageEnum language = RequestAuthorizationRequestDto.LanguageEnum.IT;
        final int requestedFee = 10;

        ResponseEntity responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Cannot find a PSP with the requested parameters")
                        .detail("Cannot find a PSP with fee and language for transaction with payment token ''"),
                HttpStatus.CONFLICT
        );
        UnsatisfiablePspRequestException exception = new UnsatisfiablePspRequestException(
                PAYMENT_TOKEN,
                language,
                requestedFee
        );

        ResponseEntity<ProblemJsonDto> response = transactionsController.unsatisfiablePspRequestHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testBadGatewayExceptionHandler() {
        ResponseEntity<ProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(502)
                        .title("Bad gateway")
                        .detail(null),
                HttpStatus.BAD_GATEWAY
        );
        BadGatewayException exception = new BadGatewayException("", HttpStatus.BAD_REQUEST);
        ResponseEntity<ProblemJsonDto> response = transactionsController.badGatewayHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void testGatewayTimeoutExceptionHandler() {
        ResponseEntity<ProblemJsonDto> responseCheck = new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(504)
                        .title("Gateway timeout")
                        .detail(null),
                HttpStatus.GATEWAY_TIMEOUT
        );
        GatewayTimeoutException exception = new GatewayTimeoutException();

        ResponseEntity<ProblemJsonDto> response = transactionsController.gatewayTimeoutHandler(exception);

        assertEquals(responseCheck.getStatusCode(), response.getStatusCode());
    }

    @Test
    void shouldReturnTransactionInfoOnCorrectNotify() {
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = new TransactionId(UUID.randomUUID()).value();

        TransactionInfoDto transactionInfo = new TransactionInfoDto()
                .transactionId(transactionId)
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(100)
                                .paymentToken(paymentToken)
                )
                .status(TransactionStatusDto.NOTIFIED_OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        AddUserReceiptResponseDto expected = new AddUserReceiptResponseDto()
                .outcome(AddUserReceiptResponseDto.OutcomeEnum.OK);

        /* preconditions */
        Mockito.when(transactionsService.addUserReceipt(transactionId, addUserReceiptRequest))
                .thenReturn(Mono.just(transactionInfo));

        /* test */
        ResponseEntity<AddUserReceiptResponseDto> response = transactionsController
                .addUserReceipt(transactionId, Mono.just(addUserReceiptRequest), null).block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
    }

    @Test
    void shouldReturnProblemJsonWith400OnBadInput() {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        webTestClient.post()
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue("{}"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(400, p.getStatus()));
    }

    @Test
    void shouldReturnErrorCircuitBreakerOpen() {

        ResponseEntity error = transactionsController.openStateHandler().block();

        // Verify status code and response
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
    }

    @Test
    void shouldReturnResponseEntityWithPartyConfigurationFault() {
        CtFaultBean faultBean = faultBeanWithCode(PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue());
        ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PartyConfigurationFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE,
                responseEntity.getBody().getFaultCodeCategory()
        );
        assertEquals(
                PartyConfigurationFaultDto.PPT_DOMINIO_DISABILITATO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithValidationFault() {
        CtFaultBean faultBean = faultBeanWithCode(ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue());

        ResponseEntity<ValidationFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<ValidationFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.PAYMENT_UNKNOWN, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                ValidationFaultDto.PPT_DOMINIO_SCONOSCIUTO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithGatewayFault() {
        CtFaultBean faultBean = faultBeanWithCode(GatewayFaultDto.PAA_SYSTEM_ERROR.getValue());

        ResponseEntity<GatewayFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<GatewayFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                GatewayFaultDto.PAA_SYSTEM_ERROR.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithPartyTimeoutFault() {
        CtFaultBean faultBean = faultBeanWithCode(PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue());
        ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PartyTimeoutFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, responseEntity.getStatusCode());
        assertEquals(FaultCategoryDto.GENERIC_ERROR, responseEntity.getBody().getFaultCodeCategory());
        assertEquals(
                PartyTimeoutFaultDto.PPT_STAZIONE_INT_PA_IRRAGGIUNGIBILE.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithPaymentStatusFault() {
        CtFaultBean faultBean = faultBeanWithCode(PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue());
        ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto> responseEntity = (ResponseEntity<PaymentStatusFaultPaymentProblemJsonDto>) transactionsController
                .nodoErrorHandler(
                        new NodoErrorException(faultBean)
                );

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                FaultCategoryDto.PAYMENT_UNAVAILABLE,
                responseEntity.getBody().getFaultCodeCategory()
        );
        assertEquals(
                PaymentStatusFaultDto.PAA_PAGAMENTO_IN_CORSO.getValue(),
                responseEntity.getBody().getFaultCodeDetail().getValue()
        );
    }

    @Test
    void shouldReturnResponseEntityWithGenericGatewayFault() {
        CtFaultBean faultBean = faultBeanWithCode("UNKNOWN_ERROR");
        ResponseEntity<ProblemJsonDto> responseEntity = (ResponseEntity<ProblemJsonDto>) transactionsController
                .nodoErrorHandler(new NodoErrorException(faultBean));

        assertEquals(Boolean.TRUE, responseEntity != null);
        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
    }

    @Test
    void shouldReturnResponseEntityWithBadRequest() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .validationExceptionHandler(new InvalidRequestException("Some message"));
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
        assertEquals("Invalid request: Some message", responseEntity.getBody().getDetail());
    }

    @Test
    void shouldReturnResponseEntityWithNotImplemented() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .notImplemented(new NotImplementedException("Method not implemented"));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, responseEntity.getStatusCode());
        assertEquals("Method not implemented", responseEntity.getBody().getDetail());
    }

    @Test
    void shouldReturnResponseEntityWithMismatchAmount() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .amountMismatchErrorHandler(new TransactionAmountMismatchException(1, 2));

        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                "Invalid request: Transaction amount mismatch",
                responseEntity.getBody().getDetail()
        );
    }

    @Test
    void shouldReturnResponseEntityWithMismatchAllCCP() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .paymentNoticeAllCCPMismatchErrorHandler(
                        new PaymentNoticeAllCCPMismatchException("testRptID", false, true)
                );

        assertEquals(HttpStatus.CONFLICT, responseEntity.getStatusCode());
        assertEquals(
                "Invalid request: Payment notice allCCP mismatch",
                responseEntity.getBody().getDetail()
        );
    }

    @Test
    void shouldReturnResponseEntityWithInternalServerErrorForErrorGeneratingJwtToken() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .jwtTokenGenerationError(new JWTTokenGenerationException());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, responseEntity.getStatusCode());
        assertEquals("Internal server error: cannot generate JWT token", responseEntity.getBody().getDetail());

    }

    @Test
    void shouldReturnResponseEntityWithInvalidNodoResponseReceivedError() {
        ResponseEntity<ProblemJsonDto> responseEntity = transactionsController
                .invalidNodoResponse(new InvalidNodoResponseException("Invalid payment token received"));

        assertEquals(HttpStatus.BAD_GATEWAY, responseEntity.getStatusCode());
        assertEquals(
                "Invalid payment token received",
                responseEntity.getBody().getDetail()
        );
    }

    @Test
    void shouldGetTransactionInfoInAllStatuses() {

        TransactionInfoDto response = new TransactionInfoDto()
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .amount(10)
                                .reason("Reason")
                                .paymentToken("payment_token")
                ).authToken("token");

        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        Mockito.when(
                jwtTokenUtils.generateToken(
                        any(SecretKey.class),
                        anyInt(),
                        eq(new Claims(new TransactionId(transactionId), null, null))
                )
        ).thenReturn(Either.right(""));
        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
                .values()) {
            response.setStatus(TransactionStatusDto.fromValue(status.toString()));
            Mockito.when(transactionsService.getTransactionInfo(transactionId)).thenReturn(Mono.just(response));
            webTestClient.get()
                    .uri("/transactions/{trnId}", Map.of("trnId", transactionId))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(TransactionInfoDto.class)
                    .value(p -> assertEquals(status.toString(), p.getStatus().toString()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "foo@test.it",
                    "FoO@TeSt.iT",
                    "FOO@TEST.IT"
            }
    )
    void shouldHandleTransactionCreatedWithMailCaseInsensitive(String email) {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        Mockito.when(transactionsService.newTransaction(any(), any(), any()))
                .thenReturn(Mono.just(new NewTransactionResponseDto()));
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email(email)
                .idCart(TransactionTestUtils.ID_CART);
        webTestClient.post()
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("X-Client-Id", "CHECKOUT")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void shouldReturnBadRequestForInvalidMail() {
        Mockito.when(jwtTokenUtils.generateToken(any(SecretKey.class), anyInt(), any(Claims.class)))
                .thenReturn(Either.right(""));
        Mockito.when(transactionsService.newTransaction(any(), any(), any()))
                .thenReturn(Mono.just(new NewTransactionResponseDto()));
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto()
                                .rptId(TransactionTestUtils.RPT_ID)
                                .amount(TransactionTestUtils.AMOUNT)
                )
                .email("invalidMail")
                .idCart(TransactionTestUtils.ID_CART);
        webTestClient.post()
                .uri("/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(newTransactionRequestDto)
                .header("X-Client-Id", "CHECKOUT")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemJsonDto.class)
                .value(
                        p -> {
                            assertEquals(400, p.getStatus());
                            assertTrue(
                                    p.getDetail().contains(
                                            "Field error in object 'newTransactionRequestDtoMono' on field 'email'"
                                    )
                            );
                        }
                );
    }

    @Test
    void shouldReturnUnprocessableEntityForBadGatewayInSendPaymentResult() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(Mono.error(new BadGatewayException("Bad gateway", HttpStatus.BAD_GATEWAY)));

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(422, p.getStatus()));

    }

    @Test
    void shouldReturnNotFoundInSendPaymentResultForNonExistingTransaction() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(Mono.error(new TransactionNotFoundException(UUID.randomUUID().toString())));

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(404, p.getStatus()));
    }

    @Test
    void shouldReturnUnprocessableEntityInSendPaymentResultForTransactionAlreadyProcessed() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(
                        Mono.error(
                                new AlreadyProcessedException(new TransactionId(TransactionTestUtils.TRANSACTION_ID))
                        )
                );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(422, p.getStatus()));
    }

    @Test
    void shouldReturnUnprocessableEntityInSendPaymentResultForUncaughtError() {
        Mockito.when(transactionsService.addUserReceipt(eq(TransactionTestUtils.TRANSACTION_ID), any()))
                .thenReturn(Mono.error(new RuntimeException("Spooky!")));

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        webTestClient.post()
                .uri(
                        builder -> builder
                                .pathSegment("transactions", TransactionTestUtils.TRANSACTION_ID, "user-receipts")
                                .build()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", "CHECKOUT")
                .body(BodyInserters.fromValue(addUserReceiptRequest))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody(ProblemJsonDto.class)
                .value(p -> assertEquals(422, p.getStatus()));
    }

    private static CtFaultBean faultBeanWithCode(String faultCode) {
        CtFaultBean fault = new CtFaultBean();
        fault.setFaultCode(faultCode);
        return fault;
    }
}
