package io.dargent.payments.race;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dargent.payments.adapter.out.persistence.PaymentJpaAdapter;
import io.dargent.payments.domain.exception.InvalidTransitionException;
import io.dargent.payments.domain.model.BpsRate;
import io.dargent.payments.domain.model.EndToEndId;
import io.dargent.payments.domain.model.FeeBreakdown;
import io.dargent.payments.domain.model.Payment;
import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PaymentRepository;
import io.dargent.shared.money.Money;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The epic's headline proof (spec §9, playbook scenario 13): N threads race the same
 * PENDING payment through the optimistic version guard and EXACTLY ONE wins. The
 * database — not optimism — arbitrates the race (AGENTS.md §3.2). Fixed inputs
 * everywhere: the only nondeterminism is scheduling, which is the point.
 */
@SpringBootTest(
    classes = PaymentConcurrentTransitionIT.PaymentsTestConfig.class,
    properties = {"spring.jpa.hibernate.ddl-auto=validate"}
)
@Testcontainers
class PaymentConcurrentTransitionIT {

    private static final int THREADS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private PaymentRepository repository;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("io.dargent.payments.adapter.out.persistence")
    static class PaymentsTestConfig {
        @Bean
        PaymentJpaAdapter paymentJpaAdapter() {
            return new PaymentJpaAdapter();
        }

        // Boot 4.x ships no Flyway auto-config; the JPA EntityManagerFactory must
        // wait for the manual migration bean (see PaymentJpaAdapterIT for context).
        @Bean
        static BeanFactoryPostProcessor emfDependsOnFlyway() {
            return beanFactory -> {
                if (!(beanFactory instanceof DefaultListableBeanFactory dlbf)) {
                    return;
                }
                String[] emfNames = dlbf.getBeanNamesForType(EntityManagerFactory.class, true, false);
                if (emfNames.length == 0) {
                    emfNames = new String[]{"entityManagerFactory"};
                }
                for (String emfName : emfNames) {
                    if (dlbf.containsBeanDefinition(emfName)) {
                        BeanDefinition bd = dlbf.getBeanDefinition(emfName);
                        String[] existing = bd.getDependsOn() == null ? new String[0] : bd.getDependsOn();
                        String[] merged = java.util.Arrays.copyOf(existing, existing.length + 1);
                        merged[existing.length] = "flyway";
                        bd.setDependsOn(merged);
                    }
                }
            };
        }

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/payments")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            return flyway;
        }
    }

    @Test
    void concurrent_confirmations_with_version_guard_yield_exactly_one_winner() throws Exception {
        Txid txid = new Txid("ABCDEFGHIJKLMNOPQRSTUVWXY");
        UUID merchantId = UUID.randomUUID();
        Money amount = Money.of(10_000, "BRL");
        Instant createdAt = Instant.parse("2026-08-01T10:00:00Z");
        Instant expiresAt = createdAt.plusSeconds(300);
        Payment seed = Payment.create(txid, merchantId, amount, "race payment", expiresAt, createdAt);
        repository.save(seed);

        EndToEndId endToEndId = new EndToEndId("E" + "A".repeat(31));
        FeeBreakdown breakdown = FeeBreakdown.of(amount.cents(), new BpsRate(100));
        Instant when = expiresAt.minusSeconds(10);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Callable<Boolean>> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            workers.add(() -> {
                barrier.await();
                Payment loaded = repository.findByTxid(txid).orElseThrow();
                int loadedVersion = loaded.version();
                Payment confirmed = loaded.confirm(endToEndId, breakdown, when);
                return repository.updateIfVersionMatches(confirmed, loadedVersion);
            });
        }

        List<Boolean> results = new ArrayList<>(THREADS);
        for (Future<Boolean> future : executor.invokeAll(workers)) {
            results.add(future.get());
        }
        executor.shutdownNow();

        long winners = results.stream().filter(Boolean.TRUE::equals).count();
        assertThat(winners).isEqualTo(1);
        assertThat(results).containsOnly(true, false).hasSize(THREADS);

        Payment persisted = repository.findByTxid(txid).orElseThrow();
        assertThat(persisted.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(persisted.endToEndId()).isEqualTo(endToEndId);
        assertThat(persisted.fee()).isEqualTo(breakdown.fee());
        assertThat(persisted.net()).isEqualTo(breakdown.net());
        assertThat(persisted.confirmedAt()).isEqualTo(when);
        assertThat(persisted.version()).isEqualTo(1);

        // A loser re-reading sees the winner's state; re-confirming on the fresh
        // load hits the domain guard — the full lost-race contract in one test.
        Payment fresh = repository.findByTxid(txid).orElseThrow();
        assertThat(fresh.version()).isEqualTo(1);
        assertThatThrownBy(() -> fresh.confirm(endToEndId, breakdown, when))
                .isInstanceOf(InvalidTransitionException.class);
    }
}