package io.dargent.payments.persistence;

import io.dargent.payments.adapter.out.persistence.PaymentJpaAdapter;
import io.dargent.payments.domain.port.out.PaymentRepository;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Executes the shared {@link PaymentRepositoryContractSuite} against real
 * PostgreSQL 16 (Testcontainers) through the JPA adapter (S9). Flyway points at
 * the module-owned {@code classpath:db/migration/payments} location — single
 * source of DDL. JPA is auto-configured by Boot; the adapter is imported as the
 * {@link PaymentRepository} bean.
 */
@SpringBootTest(
    classes = PaymentJpaAdapterIT.PaymentsTestConfig.class,
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Testcontainers
class PaymentJpaAdapterIT extends PaymentRepositoryContractSuite {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private PaymentRepository repository;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("io.dargent.payments.adapter.out.persistence")
    static class PaymentsTestConfig {
        // Boot auto-configuration provides DataSource (from @ServiceConnection)
        // and JPA/EntityManager; the JPA adapter is the single PaymentRepository bean.
        // Flyway migrates the module-owned location before Hibernate validates — the
        // Boot 4.x Flyway auto-config moved out of the framework, so the bean is manual
        // (same pattern as apps/api MigrationIT).
        @Bean
        PaymentJpaAdapter paymentJpaAdapter() {
            return new PaymentJpaAdapter();
        }

        // Boot 4.x ships no Flyway auto-config (moved out of the framework), so the
        // migration bean is manual — and the JPA EntityManagerFactory must wait for it,
        // exactly as Boot's own EntityManagerFactoryDependsOnPostProcessor used to.
        @Bean
        static BeanFactoryPostProcessor emfDependsOnFlyway() {
            return beanFactory -> {
                if (!(beanFactory instanceof DefaultListableBeanFactory dlbf)) {
                    return;
                }
                // Boot's JPA auto-config names this bean "entityManagerFactory".
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

    @Override
    protected PaymentRepository repository() {
        return repository;
    }
}