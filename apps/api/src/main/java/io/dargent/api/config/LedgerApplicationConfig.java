package io.dargent.api.config;

import io.dargent.ledger.adapter.out.db.JdbcLedgerStore;
import io.dargent.ledger.application.EventEnvelopeReader;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import io.dargent.ledger.application.SettlementUseCase;
import io.dargent.ledger.domain.port.out.LedgerStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ledger application beans (spec §5.4–§5.6). Always-on so the ledger HTTP surface and the SQS
 * consumer share one bean graph regardless of {@code DARGENT_LEDGER_CONSUMER_ENABLED}.
 * Adapters/use cases are module classes; this class only wires them (AGENTS §2).
 */
@Configuration
public class LedgerApplicationConfig {

    @Bean
    LedgerStore ledgerStore(JdbcClient jdbc, TransactionTemplate txTemplate) {
        return new JdbcLedgerStore(jdbc, txTemplate);
    }

    @Bean
    EventEnvelopeReader eventEnvelopeReader() {
        return new EventEnvelopeReader();
    }

    @Bean
    EventIngestionUseCase eventIngestionUseCase(EventEnvelopeReader reader, LedgerStore store,
            JdbcClient jdbc, TransactionTemplate txTemplate, Clock clock) {
        return new EventIngestionUseCase(reader, store, jdbc, txTemplate, clock);
    }

    @Bean
    SettlementUseCase settlementUseCase(LedgerStore store, TransactionTemplate txTemplate, Clock clock) {
        return new SettlementUseCase(store, txTemplate, clock);
    }

    @Bean
    LedgerReconciliationUseCase ledgerReconciliationUseCase(LedgerStore store) {
        return new LedgerReconciliationUseCase(store);
    }
}
