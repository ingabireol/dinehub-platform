package com.dinehub.kitchen.config;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.messaging.RabbitTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * kitchen-service consumes payment.completed and order.cancelled, and publishes
 * kitchen.status.updated.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange eventsExchange() {
        return RabbitTopology.eventsExchange();
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return RabbitTopology.deadLetterExchange();
    }

    @Bean
    public Queue deadLetterQueue() {
        return RabbitTopology.deadLetterQueue();
    }

    @Bean
    public Binding deadLetterBinding() {
        return RabbitTopology.deadLetterBinding();
    }

    @Bean
    public Queue orderPlacedQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_KITCHEN_ORDER_PLACED);
    }

    @Bean
    public Binding orderPlacedBinding() {
        return RabbitTopology.bind(orderPlacedQueue(), eventsExchange(), DomainEvents.ORDER_PLACED);
    }

    @Bean
    public Queue paymentCompletedQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_KITCHEN_PAYMENT);
    }

    @Bean
    public Binding paymentCompletedBinding() {
        return RabbitTopology.bind(paymentCompletedQueue(), eventsExchange(),
                DomainEvents.PAYMENT_COMPLETED);
    }

    @Bean
    public Queue orderCancelledQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_KITCHEN_ORDER_CANCELLED);
    }

    @Bean
    public Binding orderCancelledBinding() {
        return RabbitTopology.bind(orderCancelledQueue(), eventsExchange(),
                DomainEvents.ORDER_CANCELLED);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
