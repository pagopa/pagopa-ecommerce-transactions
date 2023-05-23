package it.pagopa.transactions.services;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.TransferListItemDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.commands.*;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.commands.handlers.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.TransactionAmountMismatchException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException;
import it.pagopa.transactions.projections.handlers.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class TransactionsService {

    @Autowired
    private TransactionActivateHandler transactionActivateHandler;

    @Autowired
    private TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandler;

    @Autowired
    private TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandler;

    @Autowired
    private TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandler;

    @Autowired
    private TransactionUserCancelHandler transactionCancelHandler;

    @Autowired
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @Autowired
    private AuthorizationRequestProjectionHandler authorizationProjectionHandler;

    @Autowired
    private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

    @Autowired
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @Autowired
    private ClosureSendProjectionHandler closureSendProjectionHandler;

    @Autowired
    private ClosureErrorProjectionHandler closureErrorProjectionHandler;

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Autowired
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @Autowired
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @Autowired
    private CancellationRequestProjectionHandler cancellationRequestProjectionHandler;

    @Autowired
    private UUIDUtils uuidUtils;

    @Autowired
    private TransactionsUtils transactionsUtils;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> eventStoreRepository;

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "newTransaction")
    public Mono<NewTransactionResponseDto> newTransaction(
                                                          NewTransactionRequestDto newTransactionRequestDto,
                                                          ClientIdDto clientIdDto
    ) {
        ClientId clientId = ClientId.fromString(
                Optional.ofNullable(clientIdDto)
                        .map(ClientIdDto::toString)
                        .orElse(null)
        );
        log.info(
                "Initializing transaction for rptId: {}. ClientId: {}",
                newTransactionRequestDto.getPaymentNotices().get(0).getRptId(),
                clientId
        );
        TransactionActivateCommand transactionActivateCommand = new TransactionActivateCommand(
                new RptId(newTransactionRequestDto.getPaymentNotices().get(0).getRptId()),
                newTransactionRequestDto,
                clientId
        );

        return transactionActivateHandler
                .handle(transactionActivateCommand)
                .doOnNext(
                        args -> log.info(
                                "Transaction initialized for rptId: {}",
                                newTransactionRequestDto.getPaymentNotices().get(0).getRptId()
                        )
                )
                .flatMap(
                        es -> {
                            final Mono<TransactionActivatedEvent> transactionActivatedEvent = es.getT1();
                            final String authToken = es.getT2();
                            return transactionActivatedEvent
                                    .flatMap(t -> projectActivatedEvent(t, authToken));
                        }
                );
    }

    @CircuitBreaker(name = "ecommerce-db")
    @Retry(name = "getTransactionInfo")
    public Mono<TransactionInfoDto> getTransactionInfo(String transactionId) {
        log.info("Get Transaction Invoked with id {} ", transactionId);
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(
                        transaction -> new TransactionInfoDto()
                                .transactionId(transaction.getTransactionId())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.getAmount())
                                                        .reason(paymentNotice.getDescription())
                                                        .paymentToken(paymentNotice.getPaymentToken())
                                                        .rptId(paymentNotice.getRptId())
                                                        .transferList(
                                                                paymentNotice.getTransferList().stream().map(
                                                                        notice -> new TransferDto()
                                                                                .transferCategory(
                                                                                        notice.getTransferCategory()
                                                                                )
                                                                                .transferAmount(
                                                                                        notice.getTransferAmount()
                                                                                ).digitalStamp(notice.getDigitalStamp())
                                                                                .paFiscalCode(notice.getPaFiscalCode())
                                                                ).toList()
                                                        )
                                        ).toList()
                                )
                                .feeTotal(transaction.getFeeTotal())
                                .clientId(
                                        TransactionInfoDto.ClientIdEnum.valueOf(
                                                transaction.getClientId().toString()
                                        )
                                )
                                .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                                .idCart(transaction.getIdCart())
                );
    }

    @CircuitBreaker(name = "transactions-backend")
    @Retry(name = "cancelTransaction")
    public Mono<Void> cancelTransaction(String transactionId) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transaction -> {
                            TransactionUserCancelCommand transactionCancelCommand = new TransactionUserCancelCommand(
                                    null,
                                    new TransactionId(transactionId)
                            );

                            return transactionCancelHandler.handle(transactionCancelCommand);
                        }
                )
                .flatMap(
                        event -> cancellationRequestProjectionHandler
                                .handle(new TransactionUserCanceledEvent(transactionId))
                )
                .then();
    }

    @CircuitBreaker(name = "transactions-backend")
    @Retry(name = "requestTransactionAuthorization")
    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(
                                                                                 String transactionId,
                                                                                 String paymentGatewayId,
                                                                                 RequestAuthorizationRequestDto requestAuthorizationRequestDto
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transaction -> {
                            Integer amountTotal = transaction.getPaymentNotices().stream()
                                    .mapToInt(
                                            it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount
                                    ).sum();
                            log.info(
                                    "Authorization request amount validation for transactionId: {}",
                                    transactionId
                            );
                            return !amountTotal.equals(requestAuthorizationRequestDto.getAmount())
                                    ? Mono.error(
                                            new TransactionAmountMismatchException(
                                                    requestAuthorizationRequestDto.getAmount(),
                                                    amountTotal
                                            )
                                    )
                                    : Mono.just(transaction);
                        }
                )
                .flatMap(
                        transaction -> {
                            log.info(
                                    "Authorization psp validation for transactionId: {}",
                                    transactionId
                            );
                            Integer amountTotal = transaction.getPaymentNotices().stream()
                                    .mapToInt(
                                            it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount
                                    ).sum();
                            return ecommercePaymentMethodsClient
                                    .calculateFee(
                                            requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                            new CalculateFeeRequestDto()
                                                    .touchpoint(transaction.getClientId().toString())
                                                    .bin(
                                                            extractBinFromPan(requestAuthorizationRequestDto)
                                                    )
                                                    .idPspList(List.of(requestAuthorizationRequestDto.getPspId()))
                                                    .paymentAmount(amountTotal.longValue())
                                                    .primaryCreditorInstitution(
                                                            transaction.getPaymentNotices().get(0).getRptId()
                                                                    .substring(0, 11)
                                                    )
                                                    .transferList(
                                                            transaction.getPaymentNotices().get(0).getTransferList()
                                                                    .stream()
                                                                    .map(
                                                                            t -> new TransferListItemDto()
                                                                                    .creditorInstitution(
                                                                                            t.getPaFiscalCode()
                                                                                    ).digitalStamp(t.getDigitalStamp())
                                                                                    .transferCategory(
                                                                                            t.getTransferCategory()
                                                                                    )
                                                                    ).toList()
                                                    ),
                                            Integer.MAX_VALUE
                                    )
                                    .map(
                                            calculateFeeResponse -> Tuples.of(
                                                    calculateFeeResponse.getPaymentMethodName(),
                                                    calculateFeeResponse.getBundles().stream()
                                                            .filter(
                                                                    psp -> psp.getIdPsp()
                                                                            .equals(
                                                                                    requestAuthorizationRequestDto
                                                                                            .getPspId()
                                                                            )
                                                                            && psp.getTaxPayerFee()
                                                                                    .equals(
                                                                                            Long.valueOf(
                                                                                                    requestAuthorizationRequestDto
                                                                                                            .getFee()
                                                                                            )
                                                                                    )
                                                            ).findFirst()
                                            )
                                    )
                                    .filter(t -> t.getT2().isPresent())
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new UnsatisfiablePspRequestException(
                                                            new PaymentToken(transactionId),
                                                            requestAuthorizationRequestDto.getLanguage(),
                                                            requestAuthorizationRequestDto.getFee()
                                                    )
                                            )
                                    )
                                    .map(
                                            t -> Tuples.of(
                                                    transaction,
                                                    t.getT1(),
                                                    t.getT2().get()
                                            )

                                    );
                        }
                )
                .flatMap(
                        args -> {
                            it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument = args
                                    .getT1();
                            BundleDto bundle = args.getT3();
                            String paymentMethodName = args.getT2();

                            log.info(
                                    "Requesting authorization for transactionId: {}",
                                    transactionDocument.getTransactionId()
                            );

                            TransactionActivated transaction = new TransactionActivated(
                                    new TransactionId(
                                            transactionDocument.getTransactionId()
                                    ),
                                    transactionDocument.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new PaymentNotice(
                                                            new PaymentToken(
                                                                    paymentNotice.getPaymentToken()
                                                            ),
                                                            new RptId(paymentNotice.getRptId()),
                                                            new TransactionAmount(
                                                                    paymentNotice.getAmount()
                                                            ),
                                                            new TransactionDescription(
                                                                    paymentNotice.getDescription()
                                                            ),
                                                            new PaymentContextCode(
                                                                    paymentNotice
                                                                            .getPaymentContextCode()
                                                            ),
                                                            paymentNotice.getTransferList().stream()
                                                                    .map(
                                                                            transfer -> new PaymentTransferInfo(
                                                                                    transfer.getPaFiscalCode(),
                                                                                    transfer.getDigitalStamp(),
                                                                                    transfer.getTransferAmount(),
                                                                                    transfer.getTransferCategory()
                                                                            )
                                                                    ).toList()
                                                    )
                                            ).toList(),
                                    transactionDocument.getEmail(),
                                    null,
                                    null,
                                    transactionDocument.getClientId(),
                                    transactionDocument.getIdCart()
                            );

                            AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                                    transaction,
                                    requestAuthorizationRequestDto.getFee(),
                                    requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                    requestAuthorizationRequestDto.getPspId(),
                                    bundle.getPaymentMethod(),
                                    bundle.getIdBrokerPsp(),
                                    bundle.getIdChannel(),
                                    paymentMethodName,
                                    bundle.getBundleName(),
                                    paymentGatewayId,
                                    requestAuthorizationRequestDto.getDetails()
                            );

                            // FIXME Handle multiple rtpId
                            TransactionRequestAuthorizationCommand transactionRequestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                                    transaction.getPaymentNotices().get(0).rptId(),
                                    authorizationData
                            );

                            return transactionRequestAuthorizationHandler
                                    .handle(transactionRequestAuthorizationCommand)
                                    .doOnNext(
                                            res -> log.info(
                                                    "Requested authorization for transaction: {}",
                                                    transactionDocument.getTransactionId()
                                            )
                                    )
                                    .flatMap(
                                            res -> authorizationProjectionHandler
                                                    .handle(authorizationData)
                                                    .thenReturn(res)
                                    );
                        }
                );
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "updateTransactionAuthorization")
    public Mono<TransactionInfoDto> updateTransactionAuthorization(
                                                                   String encodedTransactionId,
                                                                   UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        return uuidUtils.uuidFromBase64(encodedTransactionId)
                .fold(
                        Mono::error,
                        transactionIdDecoded -> {
                            TransactionId transactionId = new TransactionId(transactionIdDecoded);
                            log.info("decoded transaction id: {}", transactionIdDecoded);
                            Mono<BaseTransaction> baseTransaction = transactionsUtils.reduceEvents(transactionId);
                            return wasTransactionAuthorized(transactionId)
                                    .<Either<TransactionInfoDto, Mono<BaseTransaction>>>flatMap(alreadyAuthorized -> {
                                        if (Boolean.FALSE.equals(alreadyAuthorized)) {
                                            return Mono.just(baseTransaction).map(Either::right);
                                        } else {
                                            return baseTransaction.map(
                                                    trx -> {
                                                        log.info(
                                                                "Transaction authorization outcome already received. Transaction status: {}",
                                                                trx.getStatus()
                                                        );
                                                        return buildTransactionInfoDto(trx);
                                                    }
                                            ).map(Either::left);
                                        }
                                    })
                                    .flatMap(
                                            either -> either.fold(
                                                    Mono::just,
                                                    tx -> baseTransaction
                                                            .flatMap(
                                                                    transaction -> updateTransactionAuthorizationStatus(
                                                                            transaction,
                                                                            updateAuthorizationRequestDto
                                                                    )
                                                            )
                                                            .cast(BaseTransactionWithPaymentToken.class)
                                                            .flatMap(
                                                                    transaction -> closePayment(
                                                                            transaction,
                                                                            updateAuthorizationRequestDto
                                                                    )
                                                            )
                                                            .map(this::buildTransactionInfoDto)
                                            )
                                    );
                        }
                );

    }

    private Mono<TransactionActivated> updateTransactionAuthorizationStatus(
                                                                            BaseTransaction transaction,
                                                                            UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequestDto
        );

        // FIXME Handle multiple rtpId
        TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        return transactionUpdateAuthorizationHandler
                .handle(transactionUpdateAuthorizationCommand)
                .doOnNext(
                        authorizationStatusUpdatedEvent -> log.info(
                                "Requested authorization update for rptId: {}",
                                transaction.getPaymentNotices().get(0).rptId()
                        )
                )
                .flatMap(
                        authorizationStatusUpdatedEvent -> authorizationUpdateProjectionHandler
                                .handle(authorizationStatusUpdatedEvent)
                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction> closePayment(
                                                                                    BaseTransactionWithPaymentToken transaction,
                                                                                    UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        ClosureSendData closureSendData = new ClosureSendData(
                transaction,
                updateAuthorizationRequestDto
        );

        TransactionClosureSendCommand transactionClosureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        return transactionSendClosureHandler
                .handle(transactionClosureSendCommand)
                .doOnNext(closureSentEvent ->
                // FIXME Handle multiple rtpId
                log.info(
                        "Requested transaction closure for rptId: {}",
                        transaction.getPaymentNotices().get(0).rptId().value()
                )
                )
                .flatMap(
                        result -> result.fold(
                                errorEvent -> errorEvent.fold(
                                        errorEventRefunded -> null, // TODO aggiornare la pagina con REFUND STATUS
                                        errorEventNoRefund -> closureErrorProjectionHandler.handle(errorEventNoRefund)
                                ),
                                closureSentEvent -> closureSentEvent.fold(
                                        closureSentEventRefunded -> null, // TODO aggiornare la pagina con REFUND STATUS
                                        closureSentEventNoRefunded -> closureSendProjectionHandler
                                                .handle(closureSentEventNoRefunded)
                                )
                        )
                );
    }

    private TransactionInfoDto buildTransactionInfoDto(
                                                       it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument
    ) {
        return new TransactionInfoDto()
                .transactionId(
                        transactionDocument
                                .getTransactionId()
                )
                .payments(
                        transactionDocument
                                .getPaymentNotices()
                                .stream()
                                .map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.getAmount())
                                                .reason(paymentNotice.getDescription())
                                                .paymentToken(paymentNotice.getPaymentToken())
                                                .rptId(paymentNotice.getRptId())
                                )
                                .toList()
                )
                .status(transactionsUtils.convertEnumeration(transactionDocument.getStatus()));

    }

    private TransactionInfoDto buildTransactionInfoDto(BaseTransaction baseTransaction) {
        return new TransactionInfoDto()
                .transactionId(baseTransaction.getTransactionId().value())
                .payments(
                        baseTransaction.getPaymentNotices()
                                .stream().map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.transactionAmount().value())
                                                .reason(paymentNotice.transactionDescription().value())
                                                .paymentToken(paymentNotice.paymentToken().value())
                                                .rptId(paymentNotice.rptId().value())
                                ).toList()
                )
                .status(transactionsUtils.convertEnumeration(baseTransaction.getStatus()));

    }

    private Mono<Boolean> wasTransactionAuthorized(
                                                   TransactionId transactionId
    ) {
        /*
         * @formatter:off
         *
         * This method determines whether transaction has been previously authorized or not
         * by searching for an authorization completed event.
         * The check is performed directly on the presence of an authorization completed event
         * and not on the fact that the transaction aggregate is an instance of `BaseTransactionWithCompletedAuthorization`
         * because a generic transaction can go in the REFUNDED or EXPIRED states without undergoing authorization
         * (the corresponding aggregates do not extend, in fact, `BaseTransactionWithCompletedAuthorization`).
         *
         * This can happen, for example, when a transaction expires before getting a payment gateway response
         * (for the EXPIRED state; if in REFUNDED that means the transaction was already refunded).
         *
         * @formatter:on
         */
        return eventStoreRepository
                .findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                )
                .map(v -> true)
                .switchIfEmpty(Mono.just(false));

    }

    @CircuitBreaker(name = "transactions-backend")
    @Retry(name = "addUserReceipt")
    public Mono<TransactionInfoDto> addUserReceipt(
                                                   String transactionId,
                                                   AddUserReceiptRequestDto addUserReceiptRequest
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(
                        transactionDocument -> {
                            TransactionActivated transaction = new TransactionActivated(
                                    new TransactionId(transactionDocument.getTransactionId()),
                                    transactionDocument.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new PaymentNotice(
                                                            new PaymentToken(paymentNotice.getPaymentToken()),
                                                            new RptId(paymentNotice.getRptId()),
                                                            new TransactionAmount(paymentNotice.getAmount()),
                                                            new TransactionDescription(paymentNotice.getDescription()),
                                                            new PaymentContextCode(
                                                                    paymentNotice.getPaymentContextCode()
                                                            ),
                                                            paymentNotice.getTransferList().stream()
                                                                    .map(
                                                                            transfer -> new PaymentTransferInfo(
                                                                                    transfer.getPaFiscalCode(),
                                                                                    transfer.getDigitalStamp(),
                                                                                    transfer.getTransferAmount(),
                                                                                    transfer.getTransferCategory()
                                                                            )
                                                                    ).toList()

                                                    )
                                            )
                                            .toList(),
                                    transactionDocument.getEmail(),
                                    null,
                                    null,
                                    transactionDocument.getClientId(),
                                    transactionDocument.getIdCart()
                            );
                            AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                                    transaction,
                                    addUserReceiptRequest
                            );
                            // FIXME Handle multiple rtpId
                            return new TransactionAddUserReceiptCommand(
                                    transaction.getPaymentNotices().get(0).rptId(),
                                    addUserReceiptData
                            );
                        }
                )
                .flatMap(
                        transactionAddUserReceiptCommand -> transactionRequestUserReceiptHandler
                                .handle(transactionAddUserReceiptCommand)
                )
                .doOnNext(
                        transactionUserReceiptRequestedEvent -> log.info(
                                "{} for transactionId: {}",
                                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT,
                                transactionUserReceiptRequestedEvent.getTransactionId()
                        )
                )
                .flatMap(
                        transactionUserReceiptRequestedEvent -> transactionUserReceiptProjectionHandler
                                .handle(transactionUserReceiptRequestedEvent)
                )
                .map(
                        transaction -> new TransactionInfoDto()
                                .transactionId(transaction.getTransactionId())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.getAmount())
                                                        .reason(paymentNotice.getDescription())
                                                        .paymentToken(paymentNotice.getPaymentToken())
                                                        .rptId(paymentNotice.getRptId())
                                        ).toList()
                                )
                                .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                )
                .doOnNext(
                        transaction -> log.info(
                                "Transaction status updated {} for transactionId: {}",
                                transaction.getStatus(),
                                transaction.getTransactionId()
                        )
                );
    }

    private Mono<NewTransactionResponseDto> projectActivatedEvent(
                                                                  TransactionActivatedEvent transactionActivatedEvent,
                                                                  String authToken
    ) {
        return transactionsActivationProjectionHandler
                .handle(transactionActivatedEvent)
                .map(
                        transaction -> new NewTransactionResponseDto()
                                .transactionId(transaction.getTransactionId().value())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.transactionAmount().value())
                                                        .reason(paymentNotice.transactionDescription().value())
                                                        .rptId(paymentNotice.rptId().value())
                                                        .paymentToken(paymentNotice.paymentToken().value())
                                                        .transferList(
                                                                paymentNotice.transferList().stream().map(
                                                                        paymentTransferInfo -> new TransferDto()
                                                                                .digitalStamp(
                                                                                        paymentTransferInfo
                                                                                                .digitalStamp()
                                                                                )
                                                                                .paFiscalCode(
                                                                                        paymentTransferInfo
                                                                                                .paFiscalCode()
                                                                                )
                                                                                .transferAmount(
                                                                                        paymentTransferInfo
                                                                                                .transferAmount()
                                                                                )
                                                                                .transferCategory(
                                                                                        paymentTransferInfo
                                                                                                .transferCategory()
                                                                                )
                                                                ).toList()
                                                        )
                                        ).toList()
                                )
                                .authToken(authToken)
                                .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId()))
                                .idCart(transaction.getTransactionActivatedData().getIdCart())
                );
    }

    NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                           it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId
    ) {
        return Optional.ofNullable(clientId).filter(Objects::nonNull)
                .map(
                        enumVal -> {
                            try {
                                return NewTransactionResponseDto.ClientIdEnum.fromValue(enumVal.toString());
                            } catch (IllegalArgumentException e) {
                                log.error("Unknown input origin ", e);
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }

    private String extractBinFromPan(RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
        return requestAuthorizationRequestDto
                .getDetails()instanceof CardAuthRequestDetailsDto cardData
                        ? cardData.getPan().substring(0, 6)
                        : null;
    }

}
