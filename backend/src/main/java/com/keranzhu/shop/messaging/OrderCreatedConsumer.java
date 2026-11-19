package com.keranzhu.shop.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keranzhu.shop.order.OrderCreatedEvent;
import com.keranzhu.shop.order.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * Handles order.created events at-least-once, effectively-once:
 * the processed_messages insert and the order update commit together,
 * so a redelivered message is recognised and skipped.
 *
 * <p>If handling throws, the transaction rolls back (the id is not recorded),
 * Spring AMQP retries with backoff, and after the last attempt the message is
 * rejected without requeue and lands in order.created.dlq.</p>
 */
@Component
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);

    private final ProcessedMessageStore processed;
    private final OrderRepository orders;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public OrderCreatedConsumer(ProcessedMessageStore processed, OrderRepository orders,
                                ObjectMapper mapper, MeterRegistry metrics) {
        this.processed = processed;
        this.orders = orders;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @RabbitListener(queues = MessagingConfig.ORDER_CREATED_QUEUE)
    @Transactional
    public void onOrderCreated(Message message) throws IOException {
        OrderCreatedEvent event = mapper.readValue(message.getBody(), OrderCreatedEvent.class);
        if (event.eventId() == null || event.orderId() == null) {
            throw new IllegalArgumentException("Malformed order.created event");
        }

        if (!processed.markFirstDelivery(event.eventId())) {
            log.info("Skipping duplicate delivery of event {}", event.eventId());
            metrics.counter("shop.consumer.duplicates", "queue", MessagingConfig.ORDER_CREATED_QUEUE).increment();
            return;
        }

        // In a fuller system this is where payment capture, emails, or fulfilment would start.
        orders.confirm(event.orderId());
        log.info("Confirmed order {} (event {})", event.orderId(), event.eventId());
    }
}
