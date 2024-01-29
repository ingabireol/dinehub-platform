package com.dinehub.order.config;

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
 * order-service consumes payment results and kitchen status changes.
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

    // --- Payment results -----------------------------------------------------
    @Bean
    public Queue paymentCompletedQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_ORDER_PAYMENT);
    }

    @Bean
    public Binding paymentCompletedBinding() {
        return RabbitTopology.bind(paymentCompletedQueue(), eventsExchange(),
                DomainEvents.PAYMENT_COMPLETED);
    }

    @Bean
    public Queue paymentFailedQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_ORDER_PAYMENT + ".failed");
    }

    @Bean
    public Binding paymentFailedBinding() {
        return RabbitTopology.bind(paymentFailedQueue(), eventsExchange(),
                DomainEvents.PAYMENT_FAILED);
    }

    // --- Kitchen status ------------------------------------------------------
    @Bean
    public Queue kitchenStatusQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_ORDER_KITCHEN);
    }

    @Bean
    public Binding kitchenStatusBinding() {
        return RabbitTopology.bind(kitchenStatusQueue(), eventsExchange(),
                DomainEvents.KITCHEN_STATUS_UPDATED);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Retry and dead-lettering are configured in application.yml under
     * {@code spring.rabbitmq.listener.simple.retry}, not in code.
     *
     * <p>Three attempts with exponential backoff, then
     * {@code default-requeue-rejected: false}, which is what actually routes the
     * message to the dead-letter exchange. Unbounded retry on a poison message
     * is worse than dead-lettering it: the consumer spins, the queue backs up
     * behind it, and nothing reports a problem because the message is
     * technically still being processed.
     */
}
