package io.dargent.payments.adapter.out.persistence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.dargent.payments.domain.model.OutboxId;
import io.dargent.payments.domain.port.out.OutboxEventStore;
import io.dargent.payments.domain.port.out.OutboxEventStore.OutboxRow;
import io.dargent.payments.domain.port.out.OutboxEventStore.RequeueResult;
import io.dargent.payments.domain.port.out.OutboxEventStore.RepublishResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JdbcClient implementation of the webhook event store (E4 spec §5.4). */
@Repository
public class JdbcOutboxEventStore implements OutboxEventStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    public RepublishResult republishSent(Instant from, Instant to, List<String> types, int maxRows, Instant now) {
        if (maxRows > 500) {
            maxRows = 500;
        }
        String sql = """
                select id, aggregate_id, type, version, payload, request_id,
                       payload->>'eventId' as event_id
                from payments.outbox
                where status = 'SENT' and published_at >= :from and published_at < :to
                """;
        var params = new java.util.HashMap<String, Object>();
        params.put("from", Timestamp.from(from));
        params.put("to", Timestamp.from(to));
        if (types != null && !types.isEmpty()) {
            String placeholders = types.stream().map(t -> ":" + t.replace(".", "_")).collect(Collectors.joining(", "));
            sql += " and type in (" + placeholders + ")";
            for (int i = 0; i < types.size(); i++) {
                params.put(types.get(i).replace(".", "_"), types.get(i));
            }
        }
        sql += " order by published_at limit :maxRows";
        params.put("maxRows", maxRows);

        List<RepublishCandidate> candidates = jdbc.sql(sql)
                .params(params)
                .query((rs, rowNum) -> new RepublishCandidate(
                        new OutboxId(rs.getObject("id", UUID.class)),
                        rs.getString("aggregate_id"),
                        rs.getString("type"),
                        rs.getInt("version"),
                        rs.getString("payload"),
                        rs.getString("request_id"),
                        rs.getString("event_id")
                ))
                .list();

        int matched = candidates.size();
        int republished = 0;
        for (int i = 0; i < matched; i++) {
            RepublishCandidate c = candidates.get(i);
            String newEventId = c.eventId() + "-r" + (i + 1);
            try {
                int inserted = jdbc.sql("""
                        insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                        values (:id, :agg, :type, :version, :payload::jsonb, :req, 'PENDING', 0, :now)
                        """)
                        .param("id", UUID.randomUUID())
                        .param("agg", c.aggregateId())
                        .param("type", c.type())
                        .param("version", c.version())
                        .param("payload", replaceEventIdInPayload(c.payload(), newEventId))
                        .param("req", c.requestId())
                        .param("now", Timestamp.from(now))
                        .update();
                if (inserted > 0) {
                    republished++;
                }
            } catch (Exception e) {
                // Lost race or constraint violation - count as not republished
            }
        }
        return new RepublishResult(matched, republished);
    }

    private String replaceEventIdInPayload(String payload, String newEventId) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("eventId")) {
                ((ObjectNode) node).put("eventId", newEventId);
                return objectMapper.writeValueAsString(node);
            }
            return payload;
        } catch (Exception e) {
            return payload;
        }
    }

    private record RepublishCandidate(
            OutboxId id,
            String aggregateId,
            String type,
            int version,
            String payload,
            String requestId,
            String eventId
    ) {}

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