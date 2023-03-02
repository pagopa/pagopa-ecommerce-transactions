package it.pagopa.transactions.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.GATEWAY_TIMEOUT)
public class GatewayTimeoutException extends Exception {
}
