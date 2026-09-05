package io.dargent.payments.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.dargent.payments.adapter.out.messaging.SnsEventPublisher;
import io.dargent.payments.adapter.out.persistence.JdbcOutboxEventStore;
import io.dargent.payments.application.OutboxDeliveryUseCase;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Relay ITs (E6 §6/§7 IT1–IT3): real PostgreSQL 16 + LocalStack (SNS+SQS FIFO).
 * The topology (topic/queue/DLQ/redrive/subscription) is provisioned in
 * {@code @BeforeAll} the same way {@code deploy/localstack-init.sh} does, then asserted
 * via {@code GetQueueAttributes} — never assumed. Every cycle is driven through
 * {@code OutboxDeliveryUseCase.runOnce()} with the injected fixed {@link Clock}; zero sleeps.
 * Seeded rows carry the full E3 §5.6 envelope (with {@code eventId}), the wire format the
 * relay parses. Fixed inputs everywhere; the only nondeterminism is scheduling, which is
 * the point of IT3.
 */
@Testcontainers
class OutboxRelayIT {

    private static final String REGION = "us-east-1";
    private static final String TOPIC = "dargent-payments-events.fifo";
    private static final String DLQ = "dargent-payments-notify-dlq.fifo";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);
    private static final String OCCURRED_AT = "2026-08-29T10:00:00Z";
    private static final ObjectMapper MAPPER = new JsonMapper();

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static SqsClient sqs;
    private static SnsClient sns;
    private static String topicArn;
    private static String notifyUrl;
    private static OutboxDeliveryUseCase useCase;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(4);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/payments")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();

        sqs = sqsClient();
        sns = snsClient();
        provisionTopology();

        jdbc = JdbcClient.create(dataSource);
        OutboxDeliveryUseCase.Policy policy = new OutboxDeliveryUseCase.Policy(
                32, 2, 1000, Integer.MAX_VALUE,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 7);
        TransactionTemplate txTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        useCase = new OutboxDeliveryUseCase(new JdbcOutboxEventStore(jdbc),
                publisher(topicArn), MAPPER, FIXED_CLOCK, policy, txTemplate,
                new io.dargent.payments.application.PaymentsMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @AfterAll
    static void tearDown() {
        sqs.close();
        sns.close();
        dataSource.close();
    }

    /** Each test gets a fresh uniquely-named notify queue + subscription (no SQS purge
     * rate-limit hazard, deterministic isolation); outbox table truncated. */
    @BeforeEach
    void clean() {
        jdbc.sql("truncate payments.outbox").update();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        notifyUrl = createNotifyQueue(suffix);
    }

    // ------------------------------------------------------------------ IT1 happy

    /**
     * IT1 (E6 §7): seed row → {@code runOnce()} → message received from the notify queue with
     * body byte-equal to the stored jsonb text, {@code MessageGroupId = txid},
     * {@code MessageDeduplicationId = eventId}; row SENT + {@code published_at}.
     */
    @Test
    void publish_pending_outbox_row_to_queue_with_metadata_and_mark_SENT() throws Exception {
        UUID rowId = UUID.randomUUID();
        String txid = txid(1);
        String eventId = UUID.randomUUID().toString();
        String payload = envelope(eventId, txid, "payment.created");
        seed(rowId, txid, "payment.created", payload, "req-1");

        int published = useCase.runOnce(32);

        assertThat(published).isEqualTo(1);

        // queue: envelope body whose Message field is byte-equal to the stored jsonb text
        Received received = receiveSingle(notifyUrl);
        assertThat(received.body().path("Subject").asText()).isEqualTo("payment.created");
        assertThat(received.groupId()).isEqualTo(txid);
        assertThat(received.dedupeId()).isEqualTo(eventId);
        String storedText = jdbc.sql("select payload::text from payments.outbox where id = :id")
                .param("id", rowId).query(String.class).single();
        assertThat(received.body().path("Message").asText()).isEqualTo(storedText);

        Object[] row = jdbc.sql("select status, attempt_count, published_at from payments.outbox where id = :id")
                .param("id", rowId).query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getTimestamp(3)})
                .single();
        assertThat(row[0]).isEqualTo("SENT");
        assertThat(row[1]).isEqualTo(1);
        assertThat(((java.sql.Timestamp) row[2]).toInstant()).isEqualTo(FIXED_CLOCK.instant());
    }

    // ------------------------------------------------------------------ IT2 retry

    /**
     * IT2 (E6 §7): unpublishable row — publisher arrow-pointed at a non-existent topic ARN —
     * → {@code attempt_count=1}, {@code next_attempt_at ≈ now+30 s}, status stays PENDING.
     */
    @Test
    void publish_failure_bumps_attempt_and_defers_next_attempt() {
        UUID rowId = UUID.randomUUID();
        String txid = txid(2);
        String eventId = UUID.randomUUID().toString();
        seed(rowId, txid, "payment.created", envelope(eventId, txid, "payment.created"), "req-2");

        // A publisher aimed at a topic that does not exist: publish fails fast (fixed inputs)
        String brokenArn = topicArn.replace(TOPIC, TOPIC + "-missing");
        OutboxDeliveryUseCase broken = new OutboxDeliveryUseCase(
                new JdbcOutboxEventStore(jdbc), publisher(brokenArn), MAPPER, FIXED_CLOCK,
                new OutboxDeliveryUseCase.Policy(32, 2, 1000, Integer.MAX_VALUE,
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), 7),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new io.dargent.payments.application.PaymentsMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        int published = broken.runOnce(32);

        assertThat(published).isZero();
        Object[] row = jdbc.sql(
                "select status, attempt_count, next_attempt_at from payments.outbox where id = :id")
                .param("id", rowId).query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getTimestamp(3)})
                .single();
        assertThat(row[0]).isEqualTo("PENDING");
        assertThat(row[1]).isEqualTo(1);
        Instant expected = FIXED_CLOCK.instant().plusSeconds(30); // backoff(1) = 30 s
        Instant actual = ((java.sql.Timestamp) row[2]).toInstant();
        assertThat(actual).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ IT3 race

    /**
     * IT3 (E6 §7): two threads run {@code runOnce()} over the same seeds — SKIP LOCKED +
     * conditional PENDING mark is the arbitration, so each row is claimed once: no loss,
     * no double-SENT. Asserts counts, not threads.
     */
    @Test
    void concurrent_runOnce_workers_yield_no_loss_and_no_double_SENT() throws Exception {
        int n = 20;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            String tx = txid(100 + i);
            seed(id, tx, "payment.created",
                    envelope(UUID.randomUUID().toString(), tx, "payment.created"), "req-3-" + i);
            ids.add(id);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            workers.add(() -> {
                barrier.await();
                return useCase.runOnce(32);
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(workers);
        executor.shutdownNow();

        int total = 0;
        for (Future<Integer> f : futures) {
            try {
                total += f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        assertThat(total).isEqualTo(n); // no loss: every row published and marked once

        for (UUID id : ids) {
            Object[] row = jdbc.sql("select status, attempt_count from payments.outbox where id = :id")
                    .param("id", id).query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)})
                    .single();
            assertThat(row[0]).isEqualTo("SENT");
            assertThat(row[1]).isEqualTo(1); // no double-SENT
        }
        Long sent = jdbc.sql("select count(*) from payments.outbox where status='SENT'").query(Long.class).single();
        assertThat(sent).isEqualTo((long) n);
        Long pending = jdbc.sql("select count(*) from payments.outbox where status='PENDING'").query(Long.class).single();
        assertThat(pending).isZero();
    }

    // ------------------------------------------------------------------ IT4 purge

    /**
     * IT4 (E6 §7): after 60 relay cycles the retention purge runs (spec §5.4) — old SENT rows
     * (published_at older than DARGENT_OUTBOX_RETENTION_DAYS horizon) are deleted; fresh SENT and
     * any PENDING (not due) rows are kept. Purge vs relay touch disjoint statuses.
     */
    @Test
    void purge_deletes_old_sent_keeps_fresh_sent_and_pending() {
        String oldTx = txid(400);
        UUID oldSent = UUID.randomUUID();
        seedWithStatus(oldSent, oldTx, "payment.created",
                envelope(UUID.randomUUID().toString(), oldTx, "payment.created"), "req-4-old",
                "SENT", 1, "2026-08-29T09:59:00Z", "2026-08-20T00:00:00Z");
        String freshTx = txid(401);
        UUID freshSent = UUID.randomUUID();
        seedWithStatus(freshSent, freshTx, "payment.created",
                envelope(UUID.randomUUID().toString(), freshTx, "payment.created"), "req-4-fresh",
                "SENT", 1, "2026-08-29T09:59:00Z", "2026-08-29T00:00:00Z");
        String pendingTx = txid(402);
        UUID pending = UUID.randomUUID();
        // PENDING, not due — never claimed, never purged
        seedWithStatus(pending, pendingTx, "payment.created",
                envelope(UUID.randomUUID().toString(), pendingTx, "payment.created"), "req-4-pending",
                "PENDING", 0, "2099-01-01T00:00:00Z", null);

        for (int i = 0; i < 60; i++) {
            useCase.runOnce(32);
        }

        assertThat(count(oldSent)).isZero(); // beyond 7-day retention => deleted
        assertThat(count(freshSent)).isEqualTo(1); // within retention => kept
        assertThat(count(pending)).isEqualTo(1); // PENDING never purged by E6
        assertThat(status(pending)).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ helpers

    /** 25-char PIX txid, uppercase alphanumeric (Txid VO contract). */
    private static String txid(int n) {
        String base = "TXID" + String.format("%021d", n);
        return base.toUpperCase();
    }

    private static void provisionTopology() {
        // Same contract as deploy/localstack-init.sh: FIFO topic + DLQ (created once),
        // a fresh notify queue per test with redrive maxReceiveCount=5 and the SQS subscription.
        topicArn = sns.createTopic(CreateTopicRequest.builder()
                .name(TOPIC)
                .attributes(Map.of("FifoTopic", "true"))
                .build())
                .topicArn();

        String dlqUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName(DLQ)
                        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
                        .build())
                .queueUrl();
        String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    /** Creates a fresh FIFO notify queue (unique name), wires redrive + subscription onto it. */
    private static String createNotifyQueue(String suffix) {
        String name = "dargent-payments-notify-" + suffix + ".fifo";
        String url = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName(name)
                        .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
                        .build())
                .queueUrl();

        String dlqUrl = sqs.getQueueUrl(ctx -> ctx.queueName(DLQ)).queueUrl();
        String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        sqs.setQueueAttributes(ctx -> ctx.queueUrl(url)
                .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}")));

        String notifyArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(url)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        sns.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(notifyArn)
                .build());
        return url;
    }

    private static SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(
                        localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString()))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(java.time.Duration.ofSeconds(5)))
                .build();
    }

    private static SnsClient snsClient() {
        return SnsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(
                        localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString()))
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(java.time.Duration.ofSeconds(5)))
                .build();
    }

    private static SnsEventPublisher publisher(String arn) {
        return new SnsEventPublisher(arn, 2000, REGION,
                localstack.getEndpointOverride(LocalStackContainer.Service.SNS).toString(), "test", "test");
    }

    /** Seeds a PENDING, due row (next_attempt_at in the past, no clock skew with the fixed clock). */
    private static void seed(UUID id, String txid, String type, String payloadJson, String requestId) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'PENDING', 0, '2026-08-29T09:59:00Z')
                """)
                .param("id", id)
                .param("agg", txid)
                .param("type", type)
                .param("payload", payloadJson)
                .param("req", requestId)
                .update();
    }

    /** Seeds a row with explicit status, attempt count, next_attempt_at and published_at (IT4). */
    private static void seedWithStatus(UUID id, String txid, String type, String payloadJson, String requestId,
            String status, int attemptCount, String nextAttemptAt, String publishedAt) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at, published_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, :status, :attempts, :next::timestamptz, :published::timestamptz)
                """)
                .param("id", id)
                .param("agg", txid)
                .param("type", type)
                .param("payload", payloadJson)
                .param("req", requestId)
                .param("status", status)
                .param("attempts", attemptCount)
                .param("next", nextAttemptAt)
                .param("published", publishedAt)
                .update();
    }

    private static long count(UUID id) {
        return jdbc.sql("select count(*) from payments.outbox where id = :id")
                .param("id", id).query(Long.class).single();
    }

    private static String status(UUID id) {
        return jdbc.sql("select status from payments.outbox where id = :id")
                .param("id", id).query(String.class).single();
    }

    /** Full E3 §5.6 envelope payload with eventId, deterministic key order via Jackson. */
    private static String envelope(String eventId, String txid, String type) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("txid", txid);
            payload.put("merchantId", MERCHANT.toString());
            payload.put("amount", 10_000);
            payload.put("description", "relay-it");
            payload.put("expiresAt", "2026-08-30T12:05:00Z");
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("eventId", eventId);
            envelope.put("type", type);
            envelope.put("version", 1);
            envelope.put("aggregateId", txid);
            envelope.put("merchantId", MERCHANT.toString());
            envelope.put("requestId", "req-1");
            envelope.put("occurredAt", OCCURRED_AT);
            envelope.put("payload", payload);
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record Received(JsonNode body, String groupId, String dedupeId) {}

    /**
     * Receives one message from the notify queue (FIFO long-poll; SQS's own wait — not a sleep).
     */
    private Received receiveSingle(String url) {
        var resp = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(url)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(10)
                .attributeNames(QueueAttributeName.ALL)
                .build());
        assertThat(resp.messages()).hasSize(1);
        var m = resp.messages().get(0);
        JsonNode body;
        try {
            body = MAPPER.readTree(m.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<MessageSystemAttributeName, String> attrs = m.attributes();
        return new Received(body,
                attrs.get(MessageSystemAttributeName.MESSAGE_GROUP_ID),
                attrs.get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID));
    }
}