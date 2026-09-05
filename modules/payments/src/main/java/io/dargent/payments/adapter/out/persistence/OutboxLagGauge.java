package io.dargent.payments.adapter.out.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E11 §5 gauge binder for {@code dargent_outbox_lag_seconds}: the age of the oldest unpublished
 * outbox event — {@code max(now - next_attempt_at)} over PENDING/EXHAUSTED rows that are due.
 * <p>
 * Computed lazily at scrape time from Postgres (a pulled binder, no scheduler thread — P2-safe).
 * No rows due or an empty outbox renders 0.
 */
public final class OutboxLagGauge {

    private final JdbcClient jdbc;
    private final Clock clock;

    public OutboxLagGauge(JdbcClient jdbc, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.clock = clock;
        Gauge.builder("dargent.outbox.lag.seconds", this, OutboxLagGauge::queryLagSeconds)
                .register(registry);
    }

    private double queryLagSeconds() {
        Long lag = jdbc.sql("""
                select coalesce(max(extract(epoch from (:now - next_attempt_at))::bigint), 0)
                from payments.outbox
                where status in ('PENDING', 'EXHAUSTED') and next_attempt_at <= :now
                """)
                .param("now", Timestamp.from(clock.instant()))
                .query(Long.class)
                .single();
        return lag == null ? 0 : lag.doubleValue();
    }
}