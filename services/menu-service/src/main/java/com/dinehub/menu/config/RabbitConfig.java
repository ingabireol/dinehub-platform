package com.dinehub.menu.config;

import com.dinehub.common.messaging.RabbitTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * auth-service publishes {@code user.registered} and consumes nothing, so it
 * declares only the exchange and the dead-letter path.
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
    public MessageConverter jsonMessageConverter() {
        // JSON rather than Java serialisation: the payloads are a contract that
        // other services and other languages have to read.
        return new Jackson2JsonMessageConverter();
    }
}
