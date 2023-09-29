package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.utils.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

@Slf4j
public abstract class TransactionActivateHandlerCommon
        implements
        CommandHandler<TransactionActivateCommand, Mono<Tuple2<Mono<BaseTransactionEvent<?>>, String>>> {

    public static final int TRANSFER_LIST_MAX_SIZE = 5;
    protected final Integer paymentTokenTimeout;
    protected final JwtTokenUtils jwtTokenUtils;
    protected final ConfidentialMailUtils confidentialMailUtils;

    protected final int transientQueuesTTLSeconds;
    protected final int nodoParallelRequests;

    protected final TracingUtils tracingUtils;
    protected final OpenTelemetryUtils openTelemetryUtils;

    protected TransactionActivateHandlerCommon(

            Integer paymentTokenTimeout,
            JwtTokenUtils jwtTokenUtils,
            ConfidentialMailUtils confidentialMailUtils,
            int transientQueuesTTLSeconds,
            int nodoParallelRequests,
            TracingUtils tracingUtils,
            OpenTelemetryUtils openTelemetryUtils
    ) {

        this.paymentTokenTimeout = paymentTokenTimeout;
        this.jwtTokenUtils = jwtTokenUtils;
        this.confidentialMailUtils = confidentialMailUtils;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.nodoParallelRequests = nodoParallelRequests;
        this.tracingUtils = tracingUtils;
        this.openTelemetryUtils = openTelemetryUtils;
    }
}
