package com.dinehub.notification.config;

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
 * notification-service consumes and publishes nothing — it is a leaf.
 *
 * <p>That is deliberate: nothing downstream should depend on a notification
 * having been sent, so the service can be down without blocking anything.
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
    public Queue userRegisteredQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_NOTIFICATION_USER_REGISTERED);
    }

    @Bean
    public Binding userRegisteredBinding() {
        return RabbitTopology.bind(userRegisteredQueue(), eventsExchange(),
                DomainEvents.USER_REGISTERED);
    }

    @Bean
    public Queue orderStatusQueue() {
        return RabbitTopology.durableQueue(DomainEvents.Q_NOTIFICATION_ORDER_STATUS);
    }

    @Bean
    public Binding orderStatusBinding() {
        return RabbitTopology.bind(orderStatusQueue(), eventsExchange(),
                DomainEvents.ORDER_STATUS_CHANGED);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
