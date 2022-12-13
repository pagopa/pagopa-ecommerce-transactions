package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.IdempotencyKey;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.nodoperpsp.model.EsitoNodoAttivaRPTRisposta;
import it.pagopa.generated.nodoperpsp.model.NodoAttivaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoTipoCodiceIdRPT;
import it.pagopa.generated.nodoperpsp.model.NodoTipoDatiPagamentoPSP;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.exceptions.NodoErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.Optional;

@Slf4j
@Component
public class NodoOperations {

  private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

  private static final String ALPHANUMERICS =
          "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired NodoPerPspClient nodoPerPspClient;

  @Autowired NodeForPspClient nodeForPspClient;

  @Autowired ActivatePaymentNoticeReq baseActivatePaymentNoticeReq;

  @Autowired NodoAttivaRPT baseNodoAttivaRPT;

  @Autowired it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

  @Autowired it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

  @Autowired NodoUtilities nodoUtilities;

  public Mono<PaymentRequestInfo> activatePaymentRequest(
          PaymentRequestInfo paymentRequestInfo,
          NewTransactionRequestDto newTransactionRequestDto) {

    RptId rptId = paymentRequestInfo.id();
    String paymentContextCode = newTransactionRequestDto.getPaymentContextCode();
    Boolean isNM3 = paymentRequestInfo.isNM3();
    Integer amount = newTransactionRequestDto.getAmount();
    String paTaxCode = paymentRequestInfo.paFiscalCode();
    String paName = paymentRequestInfo.paName();
    IdempotencyKey idempotencyKey = Optional.ofNullable(paymentRequestInfo.idempotencyKey())
            .orElseGet(
                    () ->
                            new IdempotencyKey(
                                    PSP_PAGOPA_ECOMMERCE_FISCAL_CODE, randomString(10)));
    String dueDate = paymentRequestInfo.dueDate();
    String description = paymentRequestInfo.description();

    final BigDecimal amountAsBigDecimal =
        BigDecimal.valueOf(amount / 100).setScale(2, RoundingMode.CEILING);

    return Mono.just(isNM3)
        .flatMap(
            validIsNM3 ->
                Boolean.TRUE.equals(validIsNM3)
                    ? nodoActivationForNM3PaymentRequest(
                        rptId, amountAsBigDecimal, idempotencyKey.rawValue())
                    : nodoActivationForUnknownPaymentRequest(
                        rptId,
                        amountAsBigDecimal,
                        idempotencyKey.rawValue(),
                        paymentContextCode))
        .flatMap(
            paymentToken ->
                Mono.just(
                    new PaymentRequestInfo(
                        rptId,
                        paTaxCode,
                        paName,
                        description,
                        amount,
                        dueDate,
                        isNM3,
                        paymentToken,
                        idempotencyKey)));
  }

  private Mono<String> nodoActivationForNM3PaymentRequest(
      RptId rptId, BigDecimal amount, String idempotencyKey) {
    CtQrCode qrCode = new CtQrCode();
    qrCode.setFiscalCode(rptId.getFiscalCode());
    qrCode.setNoticeNumber(rptId.getNoticeId());
    ActivatePaymentNoticeReq request = baseActivatePaymentNoticeReq;
    request.setAmount(amount);
    request.setQrCode(qrCode);
    request.setIdempotencyKey(idempotencyKey);
    return nodeForPspClient
        .activatePaymentNotice(objectFactoryNodeForPsp.createActivatePaymentNoticeReq(request))
        .flatMap(
            activatePaymentNoticeRes ->
                StOutcome.OK.value().equals(activatePaymentNoticeRes.getOutcome().value())
                    ? Mono.just(activatePaymentNoticeRes.getPaymentToken())
                    : Mono.error(
                        new NodoErrorException(activatePaymentNoticeRes.getFault())));
  }

  private Mono<String> nodoActivationForUnknownPaymentRequest(
      RptId rptId,
      BigDecimal amount,
      String idempotencyKey,
      String paymentContextCode) {
    NodoAttivaRPT nodoAttivaRPTReq = baseNodoAttivaRPT;

    NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);
    NodoTipoDatiPagamentoPSP datiPagamentoPsp =
        objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP();
    datiPagamentoPsp.setImportoSingoloVersamento(amount);
    nodoAttivaRPTReq.setDatiPagamentoPSP(datiPagamentoPsp);
    nodoAttivaRPTReq.setCodiceIdRPT(nodoTipoCodiceIdRPT);
    nodoAttivaRPTReq.setCodiceContestoPagamento(paymentContextCode);
    return nodoPerPspClient
        .attivaRPT(objectFactoryNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPTReq))
        .flatMap(
            nodoAttivaRPTResponse -> {
              final EsitoNodoAttivaRPTRisposta nodoAttivaRPTRResponse =
                  nodoAttivaRPTResponse.getNodoAttivaRPTRisposta();
              final String outcome = nodoAttivaRPTRResponse.getEsito();
              final Boolean isNM3GivenAttivaRPTRisposta =
                  StOutcome.KO.value().equals(outcome)
                      && "PPT_MULTI_BENEFICIARIO"
                          .equals(nodoAttivaRPTRResponse.getFault().getFaultCode());

              if (Boolean.TRUE.equals(isNM3GivenAttivaRPTRisposta)) {
                return nodoActivationForNM3PaymentRequest(
                    rptId, amount, idempotencyKey);
              }

              return StOutcome.OK.value().equals(nodoAttivaRPTRResponse.getEsito())
                  ? Mono.just("")
                  : Mono.error(
                      new NodoErrorException(nodoAttivaRPTRResponse.getFault()));
            });
  }

  public Integer getEuroCentsFromNodoAmount(BigDecimal amountFromNodo) {
    return amountFromNodo.multiply(BigDecimal.valueOf(100)).intValue();
  }

  private String randomString(int len) {
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
    }
    return sb.toString();
  }
}
