package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public record TransactionDocument(
        @NonNull @Id TransactionId transactionId,
        @Nullable WalletPaymentInfo walletPaymentInfo
) {
    /*
     * @formatter:off
     *
     * Warning java:S6207 - Redundant constructors/methods should be avoided in records
     * Suppressed because this constructor is just to add the `@PersistenceConstructor` annotation
     * and is currently the canonical way to add annotations to record constructors
     *
     * @formatter:on
     */
    @SuppressWarnings("java:S6207")
    @PersistenceConstructor
    public TransactionDocument {
        // Do nothing
    }
}
