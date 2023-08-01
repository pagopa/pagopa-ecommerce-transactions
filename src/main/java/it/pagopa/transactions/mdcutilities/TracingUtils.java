package it.pagopa.transactions.mdcutilities;

import it.pagopa.ecommerce.commons.domain.v1.RptId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import reactor.util.context.Context;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tracing utility class that contains helper methods to set transaction
 * information, such as transactionId and rptId list into reactor context
 */
public class TracingUtils {

    /**
     * Tracing keys enumerations that contains both MDC key and default value, set
     * in case such information are not taken from incoming request
     */
    public enum TracingEntry {
        TRANSACTION_ID("transactionId", "{transactionId-not-found}"),
        RPT_IDS("rptIds", "{rptId-not-found}");

        private final String key;

        private final String defaultValue;

        TracingEntry(
                String key,
                String defaultValue
        ) {
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public String getKey() {
            return key;
        }

        public String getDefaultValue() {
            return defaultValue;
        }
    }

    /**
     * Transaction information record
     */
    public record TransactionInfo(
            TransactionId transactionId,
            Set<RptId> rptIds
    ) {
    }

    /**
     * Set transaction information into MDC taking information from the input
     * TransactionInfo
     *
     * @param transactionInfo - the transaction information record from which
     *                        retrieve information to be set into MDC
     */
    public static Context setTransactionInfoIntoReactorContext(
                                                               TransactionInfo transactionInfo,
                                                               Context reactorContext
    ) {
        Context context = putInReactorContextIfSetToDefault(
                TracingEntry.TRANSACTION_ID,
                transactionInfo.transactionId.value(),
                reactorContext
        );
        if (!transactionInfo.rptIds.isEmpty()) {
            String stringifiedRptIdList = transactionInfo.rptIds.stream().map(RptId::value)
                    .collect(Collectors.joining(","));
            context = putInReactorContextIfSetToDefault(TracingEntry.RPT_IDS, stringifiedRptIdList, context);
        }
        return context;
    }

    /**
     * Put value into MDC if the actual MDC value is not present or set to it's
     * default value
     *
     * @param mdcEntry   - the MDC entry to be value
     * @param valueToSet - the value to set
     */
    private static Context putInReactorContextIfSetToDefault(
                                                             TracingEntry mdcEntry,
                                                             String valueToSet,
                                                             Context reactorContext
    ) {
        Context currentContext = reactorContext;
        if (mdcEntry.getDefaultValue()
                .equals(reactorContext.getOrDefault(mdcEntry.getKey(), mdcEntry.getDefaultValue()))) {
            currentContext = reactorContext.put(mdcEntry.getKey(), valueToSet);
        }
        return currentContext;
    }
}
