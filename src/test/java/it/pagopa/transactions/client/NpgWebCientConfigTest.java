package it.pagopa.transactions.client;

import io.opentelemetry.api.trace.Tracer;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.generated.npg.v1.ApiClient;
import it.pagopa.ecommerce.commons.generated.npg.v1.api.PaymentServicesApi;
import it.pagopa.transactions.configurations.NpgWebClientsConfig;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider.OBJECT_MAPPER;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class NpgWebClientConfigTest {

    @Test
    void testNpgWebClientConfigApi() {
        NpgWebClientsConfig config = new NpgWebClientsConfig();
        PaymentServicesApi api = config.npgWebClient("localhost/test", 10000, 10000);
        Assert.assertNotNull(api);
        Assert.assertEquals(ApiClient.class, api.getApiClient().getClass());
    }

    @Test
    void testNpgWebClientConfigNpgClient() {
        NpgWebClientsConfig config = new NpgWebClientsConfig();
        PaymentServicesApi api = config.npgWebClient("localhost/test", 10000, 10000);
        NpgClient npgClient = config.npgClient(api, "test-key", mock(Tracer.class), OBJECT_MAPPER);
        Assert.assertNotNull(npgClient);
        Assert.assertEquals(NpgClient.class, npgClient.getClass());
    }

}
