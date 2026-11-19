package com.keranzhu.shop.outbox;

import com.keranzhu.shop.messaging.MessagingConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Relays outbox rows to RabbitMQ. A row is marked published only after the broker
 * confirms it (publisher confirm = ack, and the message was not returned as unroutable).
 * If the app crashes between publish and marking, the row is sent again on the next poll,
 * which is why consumers de-duplicate by eventId.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Clock clock = Clock.systemUTC();

    public OutboxPublisher(OutboxRepository outbox, RabbitTemplate rabbit, TransactionTemplate tx,
                           MeterRegistry metrics, @Value("${app.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.tx = tx;
        this.batchSize = batchSize;
        Gauge.builder("shop.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet confirmed by RabbitMQ")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    public void poll() {
        Integer published = tx.execute(status -> publishBatch());
        if (published != null && published > 0) {
            log.debug("Published {} outbox events", published);
        }
    }

    /** Publishes one batch; returns how many rows were confirmed. Visible for tests. */
    int publishBatch() {
        List<OutboxEvent> batch = outbox.lockNextBatch(batchSize);
        int confirmed = 0;
        for (OutboxEvent event : batch) {
            if (publishAndConfirm(event)) {
                event.markPublished(clock.instant());
                confirmed++;
            } else {
                event.recordFailedAttempt();
            }
        }
        return confirmed;
    }

    private boolean publishAndConfirm(OutboxEvent event) {
        Message message = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.getEventId())
                .setType(event.getEventType())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlation = new CorrelationData(event.getEventId());
        try {
            rabbit.send(MessagingConfig.EXCHANGE, event.getEventType(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                log.warn("Broker nacked event {}: {}", event.getEventId(), confirm.getReason());
                return false;
            }
            if (correlation.getReturned() != null) {
                log.warn("Event {} was unroutable", event.getEventId());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Publishing event {} failed: {}", event.getEventId(), e.toString());
            return false;
        }
    }
}
