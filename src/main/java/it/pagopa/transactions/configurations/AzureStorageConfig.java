package it.pagopa.transactions.configurations;

import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.util.HttpClientOptions;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueClientBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureStorageConfig {
    @Bean("transactionActivatedQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionActivatedQueueAsyncClient(
                                                                 @Value(
                                                                     "${azurestorage.connectionstringtransient}"
                                                                 ) String storageConnectionString,
                                                                 @Value(
                                                                     "${azurestorage.queues.transactionexpiration.name}"
                                                                 ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionRefundQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionRefundQueueAsyncClient(
                                                              @Value(
                                                                  "${azurestorage.connectionstringtransient}"
                                                              ) String storageConnectionString,
                                                              @Value(
                                                                  "${azurestorage.queues.transactionrefund.name}"
                                                              ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionClosureRetryQueueAsyncClient")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClient(
                                                                    @Value(
                                                                        "${azurestorage.connectionstringtransient}"
                                                                    ) String storageConnectionString,
                                                                    @Value(
                                                                        "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                    ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionClosureQueueAsyncClient")
    public QueueAsyncClient transactionClosureQueueAsyncClient(
                                                               @Value(
                                                                   "${azurestorage.connectionstringtransient}"
                                                               ) String storageConnectionString,
                                                               @Value(
                                                                   "${azurestorage.queues.transactionclosepayment.name}"
                                                               ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionNotificationRequestedQueueAsyncClient")
    public QueueAsyncClient transactionNotificationRequestedQueueAsyncClient(
                                                                             @Value(
                                                                                 "${azurestorage.connectionstringtransient}"
                                                                             ) String storageConnectionString,
                                                                             @Value(
                                                                                 "${azurestorage.queues.transactionnotificationrequested.name}"
                                                                             ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    private QueueAsyncClient buildQueueAsyncClient(
                                                   String storageConnectionString,
                                                   String queueName
    ) {
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .httpLogOptions(
                        QueueClientBuilder.getDefaultHttpLogOptions()
                                .setLogLevel(HttpLogDetailLevel.HEADERS)
                                .addAllowedHeaderName("traceparent")
                )
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }
}
