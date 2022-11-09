package it.pagopa.transactions.mdcutilities;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.UriTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@Slf4j
public class MDCFilter implements WebFilter {

    public static final String CONTEXT_KEY = "contextKey";
    public static final String TRANSACTION_ID = "transactionId";
    public static final String PAYMENT_CONTEXT_CODE = "paymentContextCode";
    public static final String RPT_ID = "rptId";

    private ServerWebExchange decorate(ServerWebExchange exchange) {

        final ServerHttpRequest decoratedRequest = new MDCCachingValuesServerHttpRequestDecorator(exchange.getRequest());

        return new ServerWebExchangeDecorator(exchange) {

            @Override
            public ServerHttpRequest getRequest() {
                return decoratedRequest;
            }

        };
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Map<String,String> transactionMap = getTransactionId(exchange.getRequest());

        ServerWebExchange serverWebExchange = decorate(exchange);
        return chain.filter(serverWebExchange)
                .doOnEach(logOnEach(r -> {
                        log.info("{} {} {}", request.getMethod(), request.getURI(), ((MDCCachingValuesServerHttpRequestDecorator)serverWebExchange.getRequest()).getInfoFromValuesMap());
                        ((MDCCachingValuesServerHttpRequestDecorator)serverWebExchange.getRequest()).getObjectAsMap().forEach((k,v) -> transactionMap.put(k,v.toString()));
                }))
                .contextWrite(Context.of(CONTEXT_KEY, UUID.randomUUID().toString()))
                .contextWrite(Context.of(TRANSACTION_ID, transactionMap.getOrDefault(TRANSACTION_ID, "")))
                .contextWrite(Context.of(PAYMENT_CONTEXT_CODE, transactionMap.getOrDefault(PAYMENT_CONTEXT_CODE, "")))
                .contextWrite(Context.of(RPT_ID, transactionMap.getOrDefault(RPT_ID, "")));
    }

    private Map<String, String> getTransactionId(ServerHttpRequest request) {
        UriTemplate uriTemplatePCC = new UriTemplate("/transactions/payment-context-codes/{paymentContextCode}/activation-results");
        UriTemplate uriTemplateStandard = new UriTemplate("/transactions/{transactionId}");
        return uriTemplatePCC.matches(request.getPath().value()) ? uriTemplatePCC.match(request.getPath().value()) : uriTemplateStandard.match(request.getPath().value());
    }

    public static <T> Consumer<Signal<T>> logOnEach(Consumer<T> logStatement) {
        return signal -> {
            String contextValue = signal.getContextView().get(CONTEXT_KEY);
            String rptId = signal.getContextView().get(RPT_ID);
            String paymentContextCode = signal.getContextView().get(PAYMENT_CONTEXT_CODE);
            String transactionId = signal.getContextView().get(TRANSACTION_ID);
            try {
                fillMDC(contextValue, rptId, paymentContextCode, transactionId);
            } finally {
                logStatement.accept(signal.get());
            }
        };
    }

    public static <T> Consumer<Signal<T>> logOnNext(Consumer<T> logStatement) {
        return signal -> {
            if (!signal.isOnNext()) return;
            String contextValue = signal.getContextView().get(CONTEXT_KEY);
            String rptId = signal.getContextView().get(RPT_ID);
            String paymentContextCode = signal.getContextView().get(PAYMENT_CONTEXT_CODE);
            String transactionId = signal.getContextView().get(TRANSACTION_ID);
            try {
                fillMDC(contextValue, rptId, paymentContextCode, transactionId);
            } finally {
                logStatement.accept(signal.get());
            }
        };
    }

    private static void fillMDC(String contextValue, String rptId, String paymentContextCode, String transactionId) {
        MDC.putCloseable(CONTEXT_KEY, contextValue);
        MDC.putCloseable(RPT_ID, rptId);
        MDC.putCloseable(PAYMENT_CONTEXT_CODE, paymentContextCode);
        MDC.putCloseable(TRANSACTION_ID, transactionId);
    }

}