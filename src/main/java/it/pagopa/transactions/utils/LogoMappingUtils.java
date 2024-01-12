package it.pagopa.transactions.utils;

import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.BrandLogoConfig;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;

@Component
public class LogoMappingUtils {

    private final Map<CardAuthRequestDetailsDto.BrandEnum, URI> pgsBrandConfMap;

    private final Map<String, URI> npgPaymentCircuitLogoMap;

    @Autowired
    public LogoMappingUtils(
            Map<CardAuthRequestDetailsDto.BrandEnum, URI> pgsBrandConfMap,
            Map<String, URI> npgPaymentCircuitLogoMap
    ) {
        this.pgsBrandConfMap = pgsBrandConfMap;
        this.npgPaymentCircuitLogoMap = npgPaymentCircuitLogoMap;
    }

    public URI getLogo(AuthorizationRequestData authRequestedData) {
        RequestAuthorizationRequestDetailsDto authorizationRequestDetailsDto = authRequestedData.authDetails();
        return switch (authorizationRequestDetailsDto) {
            case CardAuthRequestDetailsDto details -> pgsBrandConfMap.get(details.getBrand());
            case CardsAuthRequestDetailsDto ignored -> {
                URI unknown = npgPaymentCircuitLogoMap.get(BrandLogoConfig.UNKNOWN_LOGO_KEY);
                yield npgPaymentCircuitLogoMap.getOrDefault(authRequestedData.brand(), unknown);
            }
            case WalletAuthRequestDetailsDto ignored -> {
                URI unknown = npgPaymentCircuitLogoMap.get(BrandLogoConfig.UNKNOWN_LOGO_KEY);
                yield npgPaymentCircuitLogoMap.getOrDefault(authRequestedData.brand(), unknown);
            }
            case ApmAuthRequestDetailsDto ignored -> {
                URI unknown = npgPaymentCircuitLogoMap.get(BrandLogoConfig.UNKNOWN_LOGO_KEY);
                yield npgPaymentCircuitLogoMap.getOrDefault(authRequestedData.brand(), unknown);
            }
            default -> throw new InvalidRequestException("Cannot retrieve logo for input authorization request detail: [%s]".formatted(authorizationRequestDetailsDto));
        };
    }
}
