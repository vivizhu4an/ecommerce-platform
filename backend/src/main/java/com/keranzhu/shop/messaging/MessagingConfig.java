package com.keranzhu.shop.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology:
 * <pre>
 *   shop.events (topic) --order.created--> order.created.q
 *                                               | rejected after retries
 *                                               v
 *   shop.events.dlx (direct) --order.created--> order.created.dlq
 * </pre>
 */
@Configuration
public class MessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

    public static final String EXCHANGE = "shop.events";
    public static final String DEAD_LETTER_EXCHANGE = "shop.events.dlx";
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
    public static final String ORDER_CREATED_QUEUE = "order.created.q";
    public static final String ORDER_CREATED_DLQ = "order.created.dlq";

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_CREATED_ROUTING_KEY)
                .build();
    }

    @Bean
    Queue orderCreatedDlq() {
        return QueueBuilder.durable(ORDER_CREATED_DLQ).build();
    }

    @Bean
    Binding orderCreatedBinding() {
        return BindingBuilder.bind(orderCreatedQueue()).to(eventsExchange()).with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    Binding orderCreatedDlqBinding() {
        return BindingBuilder.bind(orderCreatedDlq()).to(deadLetterExchange()).with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.warn("Message returned as unroutable: key={} reply={}",
                returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
