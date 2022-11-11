package it.pagopa.transactions.commands.handlers;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionActivationRequestedData;
import it.pagopa.transactions.documents.TransactionActivationRequestedEvent;
import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import it.pagopa.transactions.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;

import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.TransactionEventCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionInitializerHandlerTest {

  private final PaymentRequestsInfoRepository paymentRequestInfoRepository =
      Mockito.mock(PaymentRequestsInfoRepository.class);

  private final EcommerceSessionsClient ecommerceSessionsClient =
      Mockito.mock(EcommerceSessionsClient.class);

  private final TransactionsEventStoreRepository<TransactionActivatedData>
      transactionEventActivatedStoreRepository =
          Mockito.mock(TransactionsEventStoreRepository.class);

  private final TransactionsEventStoreRepository<TransactionActivationRequestedData>
      transactionEventActivationRequestedStoreRepository =
          Mockito.mock(TransactionsEventStoreRepository.class);

  private final NodoOperations nodoOperations = Mockito.mock(NodoOperations.class);

  private final QueueAsyncClient transactionClosureSentEventQueueClient =
      Mockito.mock(QueueAsyncClient.class);

  private final TransactionActivateHandler handler =
      new TransactionActivateHandler(
          paymentRequestInfoRepository,
          transactionEventActivatedStoreRepository,
          transactionEventActivationRequestedStoreRepository,
          ecommerceSessionsClient,
          nodoOperations,
          transactionClosureSentEventQueueClient,
          120);

  @Test
  void shouldHandleCommandForNM3CachedPaymentRequest() {
    RptId rptId = new RptId("77777777777302016723749670035");
    IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
    String transactionId = UUID.randomUUID().toString();
    String paymentToken = UUID.randomUUID().toString();
    String sessionToken = UUID.randomUUID().toString();
    String paName = "paName";
    String paTaxcode = "77777777777";
    String description = "Description";
    Integer amount = Integer.valueOf(1000);

    NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
    requestDto.setRptId(rptId.value());
    requestDto.setEmail("jhon.doe@email.com");
    requestDto.setAmount(1200);
    requestDto.setPaymentContextCode(UUID.randomUUID().toString().replace("-", ""));
    TransactionActivateCommand command = new TransactionActivateCommand(rptId, requestDto);

    PaymentRequestInfo paymentRequestInfoCached =
        new PaymentRequestInfo(
            rptId,
            paTaxcode,
            paName,
            description,
            amount,
            null,
            true,
            paymentToken,
            idempotencyKey);

    SessionDataDto sessionDataDto =
        new SessionDataDto()
            .email(requestDto.getEmail())
            .sessionToken(sessionToken)
            .paymentToken(paymentToken)
            .rptId(rptId.value())
            .transactionId(transactionId);

    TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent();
    transactionActivatedEvent.setTransactionId(transactionId);
    transactionActivatedEvent.setEventCode(TransactionEventCode.TRANSACTION_ACTIVATED_EVENT);
    transactionActivatedEvent.setPaymentToken(paymentToken);
    transactionActivatedEvent.setRptId(rptId.value());

    /** preconditions */
    Mockito.when(paymentRequestInfoRepository.findById(rptId))
        .thenReturn(Optional.of(paymentRequestInfoCached));
    Mockito.when(transactionEventActivatedStoreRepository.save(any()))
        .thenReturn(Mono.just(transactionActivatedEvent));
    Mockito.when(paymentRequestInfoRepository.save(any(PaymentRequestInfo.class)))
        .thenReturn(paymentRequestInfoCached);
    Mockito.when(ecommerceSessionsClient.createSessionToken(any()))
        .thenReturn(Mono.just(sessionDataDto));
    Mockito.when(
            transactionClosureSentEventQueueClient.sendMessageWithResponse(
                any(BinaryData.class), any(), any()))
        .thenReturn(queueSuccessfulResponse());

    /** run test */
    Tuple3<
            Mono<TransactionActivatedEvent>,
            Mono<TransactionActivationRequestedEvent>,
            SessionDataDto>
        response = handler.handle(command).block();

    /** asserts */
    Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
    Mockito.verify(ecommerceSessionsClient, Mockito.times(1)).createSessionToken(any());

    assertEquals(sessionDataDto.getRptId(), response.getT3().getRptId());
    assertEquals(sessionDataDto.getPaymentToken(), response.getT3().getPaymentToken());
    assertEquals(paymentRequestInfoCached.paymentToken(), response.getT3().getPaymentToken());
    assertNotNull(paymentRequestInfoCached.id());
  }

  @Test
  void transactionsProjectionTests() {
    String TEST_RPTID = "77777777777302016723749670035";
    String TEST_TOKEN = "token";

    TransactionsProjection<NewTransactionResponseDto> transactionsProjection =
        new TransactionsProjection<>();
    transactionsProjection.setData(
        new NewTransactionResponseDto()
            .amount(1)
            .rptId(TEST_RPTID)
            .paymentToken(TEST_TOKEN)
            .authToken(TEST_TOKEN)
            .reason(""));

    TransactionsProjection<NewTransactionResponseDto> differentTransactionsProjection =
        new TransactionsProjection<>();
    differentTransactionsProjection.setData(
        new NewTransactionResponseDto()
            .amount(1)
            .rptId(TEST_RPTID)
            .paymentToken(TEST_TOKEN)
            .authToken(TEST_TOKEN)
            .reason(""));

    differentTransactionsProjection.setRptId(new RptId(TEST_RPTID));

    assertFalse(transactionsProjection.equals(differentTransactionsProjection));
    assertEquals(
        Boolean.TRUE,
        transactionsProjection.getData().equals(differentTransactionsProjection.getData()));
  }

  private Mono<Response<SendMessageResult>> queueSuccessfulResponse() {
    return Mono.just(
        new Response<>() {
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
