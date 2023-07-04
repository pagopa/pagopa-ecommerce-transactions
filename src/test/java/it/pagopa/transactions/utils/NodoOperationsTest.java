package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.generated.transactions.model.*;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.configurations.NodoConfig;
import it.pagopa.transactions.exceptions.InvalidNodoResponseException;
import it.pagopa.transactions.exceptions.NodoErrorException;
import org.jetbrains.annotations.TestOnly;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class NodoOperationsTest {
    @InjectMocks
    private NodoOperations nodoOperations;

    @Mock
    NodeForPspClient nodeForPspClient;

    @Mock
    NodoConfig nodoConfig;

    @Mock
    it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

    @Captor
    ArgumentCaptor<ActivatePaymentNoticeV2Request> activatePaymentNoticeReqArgumentCaptor;

    @Test
    void shouldActiveNM3PaymentRequest() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithFieldIbanAppoggioWithoutWantedABI() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT41B0000100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithNotAllMatchClause() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT41B00001008998761132355672");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void allCCPTrue_LightWeightCheckTrue() {
        nodoOperations.setLightAllCCPCheck(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT22U0760100899996122235789");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void allCCPFalse_LightWeightCheckTrue_NoIban() {
        nodoOperations.setLightAllCCPCheck(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT22U0760200899996122235789");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void allCCPFalse_LightWeightCheckTrue_NoIbanAndIBANAPPOGGIOPresent() {
        nodoOperations.setLightAllCCPCheck(true);
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);

        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT22U0760200899996122235789");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT20U0760100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);

        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithFieldIbanAppoggioWithOnlyOneWantedABI() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT41B00060100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithWantedABI() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT20U0760100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithUnwantedMetadata() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("test_key");
        ctMapEntry_1.setValue("test_value");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestIsIbanFailOnMetadata() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT20U0760100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue(null);
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestIsIbanFailTooShort() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT20U0760");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT20U076010000000000089987611323556");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(false, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithWantedABIOnTransferIban() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT41B1230100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT20U0850100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithBothFieldIbanAppoggioWithWantedABIOnTransferIbanAndIbanAppoggio() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT20U0760100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        CtMetadata metadata = new CtMetadata();
        CtMapEntry ctMapEntry = new CtMapEntry();
        ctMapEntry.setKey("IBANAPPOGGIO");
        ctMapEntry.setValue("IT41B0000100899876113235567");
        metadata.getMapEntry().add(ctMapEntry);
        ctTransferPSPV2.setMetadata(metadata);
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        CtMetadata metadata_1 = new CtMetadata();
        CtMapEntry ctMapEntry_1 = new CtMapEntry();
        ctMapEntry_1.setKey("IBANAPPOGGIO");
        ctMapEntry_1.setValue("IT20U0760100899876113235567");
        metadata_1.getMapEntry().add(ctMapEntry_1);
        ctTransferPSPV2_1.setMetadata(metadata_1);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(true, response.isAllCCP());
    }

    @Test
    void shouldActiveNM3PaymentRequestWithIdCartNull() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote() == null))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        null
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
    }

    @Test
    void shouldNotActiveNM3PaymentRequestdueFaultError() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";

        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        CtFaultBean ctFault = objectFactoryUtil.createCtFaultBean();
        ctFault.setFaultCode("PPT_PAGAMENTO_IN_CORSO");
        activatePaymentRes.setFault(ctFault);
        activatePaymentRes.setOutcome(StOutcome.KO);

        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeV2Request(activatePaymentReq));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* Test / asserts */
        Mono<PaymentRequestInfo> paymentRequestInfoMono = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                );

        Assert.assertThrows(
                NodoErrorException.class,
                paymentRequestInfoMono::block
        );
    }

    @Test
    void shouldNotActiveNM3PaymentRequestForMissingPaymentToken() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String transactionId = UUID.randomUUID().toString();
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal amountBigDec = BigDecimal.valueOf(amount);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";

        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(null);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);

        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request(Mockito.any()))
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeV2Request(activatePaymentReq));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* Test / asserts */
        Mono<PaymentRequestInfo> paymentRequestInfoMono = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                );

        InvalidNodoResponseException exception = Assert.assertThrows(
                InvalidNodoResponseException.class,
                paymentRequestInfoMono::block
        );
        assertEquals("No payment token received", exception.getErrorDescription());
    }

    @Test
    void shouldTrasformNodoAmountWithCentInEuroCent() {

        BigDecimal amountFromNodo = BigDecimal.valueOf(19.91);
        Integer amount = nodoOperations.getEuroCentsFromNodoAmount(amountFromNodo);
        assertEquals(1991, amount);
    }

    @Test
    void shouldTrasformNodoAmountWithoutCentInEuroCent() {

        BigDecimal amountFromNodo = BigDecimal.valueOf(19.00);
        Integer amount = nodoOperations.getEuroCentsFromNodoAmount(amountFromNodo);
        assertEquals(1900, amount);
    }

    @Test
    void shouldConvertAmountCorrectly() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String paymentNotice = "302000100000009424";
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        Integer amount = 1234;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();

        BigDecimal amountBigDec = BigDecimal.valueOf(amount.doubleValue() / 100)
                .setScale(2, RoundingMode.CEILING);

        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(paTaxCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(amountBigDec);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(paTaxCode);
        activatePaymentRes.setTotalAmount(amountBigDec);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(objectFactoryUtil.createCtTransferListPSPV2());

        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(activatePaymentNoticeReqArgumentCaptor.capture())
        )
                .thenReturn(objectFactoryUtil.createActivatePaymentNoticeV2Request(activatePaymentReq));

        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        // check amount saved into PaymentRequestInfo object
        assertEquals(1234, response.amount());
        // Check amount sent into Nodo requests
        assertEquals(
                BigDecimal.valueOf(12.34).doubleValue(),
                activatePaymentNoticeReqArgumentCaptor.getValue().getAmount().doubleValue()
        );
    }

    @Test
    void shouldReturnFiscalCodeEcommerce() {

        /* preconditions */
        String ecommerceFiscalCode = "00000000000";
        NodoConnectionString nodoConnectionString = new NodoConnectionString();
        nodoConnectionString.setIdBrokerPSP(ecommerceFiscalCode);
        Mockito.when(nodoConfig.nodoConnectionString()).thenReturn(nodoConnectionString);

        /* test */
        String maybeEcommerceFiscalCode = nodoOperations
                .getEcommerceFiscalCode();

        /* asserts */
        assertEquals(ecommerceFiscalCode, maybeEcommerceFiscalCode);
    }

    @Test
    void shouldReturnRandomStringforIdempotencykey() {

        /* test */
        String randomStringToIdempotencyKey = nodoOperations
                .generateRandomStringToIdempotencyKey();

        /* asserts */
        assertEquals(10, randomStringToIdempotencyKey.length());
    }

    @Test
    void shouldGetTheUpdatedAmount() {
        RptId rptId = new RptId("77777777777302016723749670035");
        IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
        String paymentToken = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String paTaxCode = "77777777777";
        String description = "Description";
        int amount = 1000;
        String idCart = "idCart";
        it.pagopa.generated.transactions.model.ObjectFactory objectFactoryUtil = new it.pagopa.generated.transactions.model.ObjectFactory();
        BigDecimal outdatedAmount = BigDecimal.valueOf(amount);
        BigDecimal updatedAmount = BigDecimal.valueOf(amount + 100);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        CtTransferListPSPV2 ctTransferListPSPV2 = objectFactoryUtil.createCtTransferListPSPV2();
        CtTransferPSPV2 ctTransferPSPV2 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2.setIdTransfer(1);
        ctTransferPSPV2.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2.setIBAN("IT41B0000100899876113235567");
        ctTransferPSPV2.setRemittanceInformation("test1");
        byte[] testByte = new byte[] {
                0,
                1,
                2,
                3
        };
        CtRichiestaMarcaDaBollo ctRichiestaMarcaDaBollo = objectFactoryUtil.createCtRichiestaMarcaDaBollo();
        ctRichiestaMarcaDaBollo.setTipoBollo("Tipo Bollo");
        ctRichiestaMarcaDaBollo.setProvinciaResidenza("RM");
        ctRichiestaMarcaDaBollo.setHashDocumento(testByte);
        CtTransferPSPV2 ctTransferPSPV2_1 = objectFactoryUtil.createCtTransferPSPV2();
        ctTransferPSPV2_1.setIdTransfer(1);
        ctTransferPSPV2_1.setFiscalCodePA(fiscalCode);
        ctTransferPSPV2_1.setTransferAmount(BigDecimal.valueOf(amount));
        ctTransferPSPV2_1.setRichiestaMarcaDaBollo(ctRichiestaMarcaDaBollo);
        ctTransferPSPV2_1.setRemittanceInformation("test1");
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2);
        ctTransferListPSPV2.getTransfer().add(ctTransferPSPV2_1);
        ActivatePaymentNoticeV2Request activatePaymentReq = objectFactoryUtil.createActivatePaymentNoticeV2Request();
        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(paymentNotice);
        activatePaymentReq.setAmount(outdatedAmount);
        activatePaymentReq.setQrCode(qrCode);

        ActivatePaymentNoticeV2Response activatePaymentRes = objectFactoryUtil.createActivatePaymentNoticeV2Response();
        activatePaymentRes.setPaymentToken(paymentToken);
        activatePaymentRes.setFiscalCodePA(fiscalCode);
        activatePaymentRes.setTotalAmount(updatedAmount);
        activatePaymentRes.setPaymentDescription(description);
        activatePaymentRes.setOutcome(StOutcome.OK);
        activatePaymentRes.setTransferList(ctTransferListPSPV2);
        /* preconditions */
        Mockito.when(nodeForPspClient.activatePaymentNoticeV2(Mockito.any()))
                .thenReturn(Mono.just(activatePaymentRes));
        Mockito.when(
                objectFactoryNodeForPsp
                        .createActivatePaymentNoticeV2Request(argThat(req -> req.getPaymentNote().equals(idCart)))
        )
                .thenAnswer(args -> objectFactoryUtil.createActivatePaymentNoticeV2Request(args.getArgument(0)));
        Mockito.when(nodoConfig.baseActivatePaymentNoticeV2Request()).thenReturn(new ActivatePaymentNoticeV2Request());

        /* test */
        PaymentRequestInfo response = nodoOperations
                .activatePaymentRequest(
                        rptId,
                        idempotencyKey,
                        amount,
                        transactionId,
                        900,
                        idCart
                )
                .block();

        /* asserts */
        Mockito.verify(nodeForPspClient, Mockito.times(1)).activatePaymentNoticeV2(Mockito.any());

        assertEquals(rptId, response.id());
        assertEquals(paymentToken, response.paymentToken());
        assertEquals(description, response.description());
        assertEquals(idempotencyKey, response.idempotencyKey());
        assertEquals(paTaxCode, response.paFiscalCode());
        assertEquals(updatedAmount.multiply(BigDecimal.valueOf(100)), BigDecimal.valueOf(response.amount()));
    }
}
