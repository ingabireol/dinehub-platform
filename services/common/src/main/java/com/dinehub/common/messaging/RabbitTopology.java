package com.dinehub.common.messaging;

import com.dinehub.common.events.DomainEvents;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.Map;

/**
 * Helpers for declaring the exchange, dead-letter path and queues.
 *
 * <p>Each service declares only the queues it consumes. Declarations are
 * idempotent in AMQP, so the exchange and dead-letter topology being declared by
 * several services is harmless and means no service depends on another having
 * started first.
 */
public final class RabbitTopology {

    private RabbitTopology() {
    }

    /** Messages that fail every retry wait here. Nothing deletes them. */
    public static final int DEFAULT_MAX_RETRIES = 3;

    public static TopicExchange eventsExchange() {
        return new TopicExchange(DomainEvents.EXCHANGE, true, false);
    }

    public static TopicExchange deadLetterExchange() {
        return new TopicExchange(DomainEvents.DLX, true, false);
    }

    public static Queue deadLetterQueue() {
        return QueueBuilder.durable(DomainEvents.DLQ).build();
    }

    public static Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with("#");
    }

    /**
     * A durable queue that dead-letters on rejection.
     *
     * <p>Durable because a queue lost on broker restart silently drops every
     * event that was waiting in it, and nothing reports that.
     */
    public static Queue durableQueue(String name) {
        return QueueBuilder.durable(name)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", DomainEvents.DLX,
                        "x-dead-letter-routing-key", name))
                .build();
    }

    public static Binding bind(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
