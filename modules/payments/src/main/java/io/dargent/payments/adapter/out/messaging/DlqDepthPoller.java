package io.dargent.payments.adapter.out.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.ObjectMapper;

/**
 * E11 §5 DLQ depth gauge poller: for each consumer queue (URL from the existing env contract)
 * resolves its dead-letter target via {@code RedrivePolicy}, then reads
 * {@code ApproximateNumberOfMessages} and publishes it as {@code dargent_dlq_messages{queue=..}}.
 * <p>
 * One queue per dead letter, tagged by DLQ queue name. Runs on a schedule in the api app, and is
 * drivable directly in ITs ({@link #poll()}) — the house runOnce pattern; a failed poll logs a
 * warning and leaves the last known depth in place (gauge never breaks the app).
 */
public final class DlqDepthPoller {

    public static final String METRIC_NAME = "dargent.dlq.messages";

    private static final Logger log = LoggerFactory.getLogger(DlqDepthPoller.class);

    private final SqsClient sqs;
    private final List<String> consumerQueueUrls;
    private final MeterRegistry registry;
    private final ObjectMapper objectMapper;
    /**
     * Stable gauge holders, one per DLQ (keyed by consumer queue URL). {@code Gauge} keeps a weak
     * reference to the state object — re-registering with a fresh holder each poll would freeze
     * the gauge at its first value, so the holder must live here and only the value is updated.
     */
    private final ConcurrentHashMap<String, AtomicLong> gaugeHolders = new ConcurrentHashMap<>();

    public DlqDepthPoller(SqsClient sqs, List<String> consumerQueueUrls,
            ObjectMapper objectMapper, MeterRegistry registry) {
        this.sqs = sqs;
        this.consumerQueueUrls = consumerQueueUrls;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    public void poll() {
        for (String consumerQueueUrl : consumerQueueUrls) {
            try {
                String dlqUrl = resolveDlqUrl(consumerQueueUrl);
                if (dlqUrl == null) {
                    continue; // no redrive policy -> nothing to gauge for this queue
                }
                String dlqName = queueNameFromUrl(dlqUrl);
                var attrs = sqs.getQueueAttributes(r -> r.queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).attributes();
                long depth = Long.parseLong(attrs.getOrDefault(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
                AtomicLong holder = gaugeHolders.computeIfAbsent(consumerQueueUrl, k -> {
                    AtomicLong fresh = new AtomicLong(depth);
                    registry.gauge(METRIC_NAME, Tags.of("queue", dlqName), fresh, AtomicLong::get);
                    return fresh;
                });
                holder.set(depth);
            } catch (RuntimeException e) {
                log.warn("DLQ poll failed queue={} error={}",
                        queueNameFromUrl(consumerQueueUrl), e.getMessage());
            }
        }
    }

    private String resolveDlqUrl(String consumerQueueUrl) {
        var attrs = sqs.getQueueAttributes(r -> r.queueUrl(consumerQueueUrl)
                .attributeNames(QueueAttributeName.REDRIVE_POLICY)).attributes();
        String policy = attrs.get(QueueAttributeName.REDRIVE_POLICY);
        if (policy == null) {
            return null;
        }
        String arn;
        try {
            arn = objectMapper.readTree(policy).path("deadLetterTargetArn").asText(null);
        } catch (Exception e) {
            log.warn("DLQ redrive policy parse failed queue={} error={}",
                    queueNameFromUrl(consumerQueueUrl), e.getMessage());
            return null;
        }
        if (arn == null || arn.isBlank()) {
            return null;
        }
        String name = arn.substring(arn.lastIndexOf(':') + 1);
        return sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
    }

    private static String queueNameFromUrl(String queueUrl) {
        int idx = queueUrl.lastIndexOf('/');
        return idx >= 0 ? queueUrl.substring(idx + 1) : queueUrl;
    }
}