package com.example.ecommerce.order.infra.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "order.exchange";
    public static final String QUEUE    = "order.created.queue";
    public static final String ROUTING_KEY = "order.created";
    public static final String DEAD_LETTER_EXCHANGE = "order.dlx";
    public static final String DEAD_LETTER_QUEUE = "order.created.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "order.created.dead";
    public static final String CANCELLED_QUEUE = "order.cancelled.queue";
    public static final String CANCELLED_ROUTING_KEY = "order.cancelled";
    public static final String CANCELLED_DEAD_LETTER_QUEUE = "order.cancelled.dlq";
    public static final String CANCELLED_DEAD_LETTER_ROUTING_KEY = "order.cancelled.dead";
    public static final String INVENTORY_CANCELLED_QUEUE = "inventory.order.cancelled.queue";
    public static final String INVENTORY_CANCELLED_DEAD_LETTER_QUEUE = "inventory.order.cancelled.dlq";
    public static final String INVENTORY_CANCELLED_DEAD_LETTER_ROUTING_KEY = "inventory.order.cancelled.dead";

    @Bean
    public TopicExchange orderExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Binding binding(Queue orderCreatedQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderCreatedQueue)
                .to(orderExchange)
                .with(ROUTING_KEY);
    }

    @Bean
    public Queue orderCancelledQueue() {
        return QueueBuilder.durable(CANCELLED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(CANCELLED_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding cancelledBinding(Queue orderCancelledQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderCancelledQueue)
                .to(orderExchange)
                .with(CANCELLED_ROUTING_KEY);
    }

    @Bean
    public Queue cancelledDeadLetterQueue() {
        return QueueBuilder.durable(CANCELLED_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding cancelledDeadLetterBinding(Queue cancelledDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(cancelledDeadLetterQueue)
                .to(deadLetterExchange)
                .with(CANCELLED_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Queue inventoryOrderCancelledQueue() {
        return QueueBuilder.durable(INVENTORY_CANCELLED_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(INVENTORY_CANCELLED_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding inventoryCancelledBinding(Queue inventoryOrderCancelledQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(inventoryOrderCancelledQueue)
                .to(orderExchange)
                .with(CANCELLED_ROUTING_KEY);
    }

    @Bean
    public Queue inventoryCancelledDeadLetterQueue() {
        return QueueBuilder.durable(INVENTORY_CANCELLED_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding inventoryCancelledDeadLetterBinding(
            Queue inventoryCancelledDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(inventoryCancelledDeadLetterQueue)
                .to(deadLetterExchange)
                .with(INVENTORY_CANCELLED_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setMandatory(true);
        return template;
    }
}
