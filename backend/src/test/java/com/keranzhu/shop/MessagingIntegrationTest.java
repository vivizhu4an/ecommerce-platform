package com.keranzhu.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keranzhu.shop.catalog.Product;
import com.keranzhu.shop.messaging.MessagingConfig;
import com.keranzhu.shop.order.OrderCreatedEvent;
import com.keranzhu.shop.order.OrderRepository;
import com.keranzhu.shop.order.OrderStatus;
import com.keranzhu.shop.order.OrderView;
import com.keranzhu.shop.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MessagingIntegrationTest extends IntegrationTestBase {

    @Autowired RabbitTemplate rabbit;
    @Autowired OrderRepository orders;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void outboxEventIsPublishedAndConsumerConfirmsOrder() {
        Product p = newProduct(5);
        String token = newUserToken();
        putInCart(token, p.getId(), 1);
        OrderView order = checkout(token, UUID.randomUUID().toString(), OrderView.class).getBody();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(orders.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateId().equals(order.id()))
                    .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        });
    }

    @Test
    void redeliveredEventIsProcessedOnlyOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();
        var event = new OrderCreatedEvent(eventId, -1L, -1L, BigDecimal.ONE, List.of());
        Message message = json(mapper.writeValueAsBytes(event), eventId);

        // Simulates the broker redelivering, or the outbox re-sending after a crash.
        rabbit.send(MessagingConfig.EXCHANGE, MessagingConfig.ORDER_CREATED_ROUTING_KEY, message);
        rabbit.send(MessagingConfig.EXCHANGE, MessagingConfig.ORDER_CREATED_ROUTING_KEY, message);
        rabbit.send(MessagingConfig.EXCHANGE, MessagingConfig.ORDER_CREATED_ROUTING_KEY, message);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_messages WHERE event_id = ?", Integer.class, eventId);
            assertThat(rows).isEqualTo(1);
            assertThat(rabbit.execute(ch -> ch.queueDeclarePassive(MessagingConfig.ORDER_CREATED_QUEUE)
                    .getMessageCount())).isZero();
        });
    }

    @Test
    void poisonMessageIsRetriedThenDeadLettered() {
        String id = UUID.randomUUID().toString();
        rabbit.send(MessagingConfig.EXCHANGE, MessagingConfig.ORDER_CREATED_ROUTING_KEY,
                json("{not valid json".getBytes(StandardCharsets.UTF_8), id));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message dead = rabbit.receive(MessagingConfig.ORDER_CREATED_DLQ, 1000);
            assertThat(dead).isNotNull();
            assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(id);
        });
    }

    private static Message json(byte[] body, String messageId) {
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId)
                .build();
    }
}
