package io.dargent.payments.adapter.out.persistence;

import io.dargent.payments.domain.model.OutboxId;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import io.dargent.payments.domain.port.out.OutboxEventStore.OutboxRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the webhook event store (E4 spec §5.4). */
@Repository
public class JdbcOutboxEventStore implements OutboxEventStore {

    private final JdbcClient jdbc;

    public JdbcOutboxEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxRow> claimPending(int batch, Instant now) {
        return jdbc.sql("""
                select id, aggregate_id, type, version, payload, request_id, attempt_count
                from payments.outbox
                where status = 'PENDING' and next_attempt_at <= :now
                order by next_attempt_at
                for update skip locked
                limit :batch
                """)
                .param("now", Timestamp.from(now))
                .param("batch", batch)
                .query((rs, rowNum) -> {
                    OutboxId id = new OutboxId(rs.getObject("id", UUID.class));
                    return new OutboxRow(
                            id,
                            rs.getString("aggregate_id"),
                            rs.getString("type"),
                            rs.getInt("version"),
                            rs.getString("payload"),
                            rs.getString("request_id"),
                            rs.getInt("attempt_count")
                    );
                })
                .list();
    }

    @Override
    public boolean markSent(OutboxId id, int attemptCount, Instant publishedAt) {
        int updated = jdbc.sql("""
                update payments.outbox
                set status = 'SENT', attempt_count = :attemptCount, published_at = :publishedAt
                where id = :id and status = 'PENDING'
                """)
                .param("id", id.value())
                .param("attemptCount", attemptCount)
                .param("publishedAt", Timestamp.from(publishedAt))
                .update();
        return updated > 0;
    }

    @Override
    public boolean markFailed(OutboxId id, int attemptCount, Instant nextAttemptAt) {
        int updated = jdbc.sql("""
                update payments.outbox
                set attempt_count = :attemptCount, next_attempt_at = :nextAttemptAt
                where id = :id and status = 'PENDING'
                """)
                .param("id", id.value())
                .param("attemptCount", attemptCount)
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .update();
        return updated > 0;
    }

    @Override
    public boolean markExhausted(OutboxId id, int attemptCount) {
        int updated = jdbc.sql("""
                update payments.outbox
                set status = 'EXHAUSTED', attempt_count = :attemptCount
                where id = :id and status = 'PENDING'
                """)
                .param("id", id.value())
                .param("attemptCount", attemptCount)
                .update();
        return updated > 0;
    }

    @Override
    public RequeueResult requeueExhausted(OutboxId id, Instant now) {
        String aggregateId = jdbc.sql("""
                update payments.outbox
                set status = 'PENDING', attempt_count = 0, next_attempt_at = :now
                where id = :id and status = 'EXHAUSTED'
                returning aggregate_id
                """)
                .param("id", id.value())
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional()
                .orElse(null);
        if (aggregateId != null) {
            return RequeueResult.requeued(aggregateId);
        }
        Integer found = jdbc.sql("select 1 from payments.outbox where id = :id")
                .param("id", id.value())
                .query(Integer.class)
                .optional()
                .orElse(null);
        return found == null ? RequeueResult.notFound() : RequeueResult.notExhaustible();
    }

    @Override
    public int purgeSent(Instant cutoff, int limit) {
        return jdbc.sql("""
                delete from payments.outbox
                where id in (
                    select id from payments.outbox
                    where status = 'SENT' and published_at < :cutoff
                    order by published_at
                    limit :limit
                )
                """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .update();
    }
}