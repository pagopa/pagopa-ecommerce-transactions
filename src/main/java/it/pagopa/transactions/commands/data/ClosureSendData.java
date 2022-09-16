package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.domain.TransactionInitialized;

public record ClosureSendData(
        TransactionInitialized transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {}
