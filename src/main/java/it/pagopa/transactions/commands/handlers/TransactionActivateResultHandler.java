package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.NodoPerPM;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.domain.TransactionActivated;
import it.pagopa.transactions.domain.TransactionActivationRequested;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import it.pagopa.transactions.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionActivateResultHandler
		implements CommandHandler<TransactionActivateResultCommand, Mono<TransactionActivatedEvent>> {

	@Autowired
	private TransactionsEventStoreRepository<TransactionActivatedData> transactionEventStoreRepository;

	@Autowired private NodoPerPM nodoPerPM;

	@Autowired private PaymentRequestsInfoRepository paymentRequestsInfoRepository;

	@Override
	public Mono<TransactionActivatedEvent> handle(TransactionActivateResultCommand command) {

		final TransactionActivationRequested transactionActivationRequested = command.getData().transactionActivationRequested();

		final String transactionId = command.getData().transactionActivationRequested().getTransactionId().value().toString();
		TransactionActivatedData data = new TransactionActivatedData();
		data.setAmount(transactionActivationRequested.getAmount().value());
		data.setDescription(transactionActivationRequested.getDescription().value());

		return Mono.just(command)
				.filterWhen(commandData -> Mono
						.just(commandData.getData().transactionActivationRequested().getStatus() == TransactionStatusDto.ACTIVATION_REQUESTED))
				.switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getRptId())))
				.flatMap(commandData -> {
					final String paymentToken = commandData.getData().activationResultData().getPaymentToken();

					return nodoPerPM.chiediInformazioniPagamento(paymentToken)
							.doOnError(throwable -> {
								log.error("chiediInformazioniPagamento failed for paymentToken {}", paymentToken);
								throw new TransactionNotFoundException("chiediInformazioniPagamento failed for paymentToken " +paymentToken);
							})
							.doOnSuccess(a -> log.info("chiediInformazioniPagamento succeded for paymentToken {}", paymentToken));
				}).flatMap(informazioniPagamentoDto -> paymentRequestsInfoRepository.findById(command.getRptId()).map(Mono::just).orElseGet(Mono::empty))
				.switchIfEmpty(Mono.error(new TransactionNotFoundException("Transaction not found for rptID " + command.getRptId().value() + " with paymentToken "+ command.getData().activationResultData().getPaymentToken())))
				.flatMap(paymentRequestInfo -> {
					log.info("paymentRequestsInfoRepository findById info for rptID {} succeeded", command.getRptId().value());
					//FIXME check why this repository is not a reactive repository
					return Mono.just(paymentRequestsInfoRepository.save(
							new PaymentRequestInfo(
									paymentRequestInfo.id(),
									paymentRequestInfo.paFiscalCode(),
									paymentRequestInfo.paName(),
									paymentRequestInfo.description(),
									paymentRequestInfo.amount(),
									paymentRequestInfo.dueDate(),
									paymentRequestInfo.isNM3(),
									command.getData().activationResultData().getPaymentToken(),
									paymentRequestInfo.idempotencyKey())
					));
				}).flatMap(saved -> {
					TransactionActivatedEvent transactionActivatedEvent =
							new TransactionActivatedEvent(
									transactionId,
									command.getData().transactionActivationRequested().getRptId().value(),
									saved.paymentToken(),
									data);
					log.info("Saving TransactionActivatedevent {}", transactionActivatedEvent);
					return transactionEventStoreRepository.save(transactionActivatedEvent);
				});
	}
}