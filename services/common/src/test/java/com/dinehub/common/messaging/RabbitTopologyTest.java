package com.dinehub.common.messaging;

import com.dinehub.common.events.DomainEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitTopologyTest {

    @Test
    @DisplayName("the events exchange is durable")
    void exchangeIsDurable() {
        // A non-durable exchange disappears on broker restart and every binding
        // with it, which silently stops delivery with no error anywhere.
        var exchange = RabbitTopology.eventsExchange();

        assertThat(exchange.getName()).isEqualTo(DomainEvents.EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    @DisplayName("a service queue dead-letters rather than looping forever")
    void queueDeadLetters() {
        var queue = RabbitTopology.durableQueue(DomainEvents.Q_PAYMENT_ORDER_PLACED);

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", DomainEvents.DLX)
                .containsEntry("x-dead-letter-routing-key", DomainEvents.Q_PAYMENT_ORDER_PLACED);
    }

    @Test
    @DisplayName("the dead-letter queue catches everything via a wildcard binding")
    void deadLetterQueueCatchesAll() {
        var binding = RabbitTopology.deadLetterBinding();

        assertThat(binding.getRoutingKey()).isEqualTo("#");
        assertThat(binding.getExchange()).isEqualTo(DomainEvents.DLX);
    }

    @Test
    @DisplayName("a binding connects a queue to the events exchange on its routing key")
    void bindsQueueToExchange() {
        var queue = RabbitTopology.durableQueue(DomainEvents.Q_ORDER_PAYMENT);
        var binding = RabbitTopology.bind(queue, RabbitTopology.eventsExchange(),
                DomainEvents.PAYMENT_COMPLETED);

        assertThat(binding.getDestination()).isEqualTo(DomainEvents.Q_ORDER_PAYMENT);
        assertThat(binding.getRoutingKey()).isEqualTo(DomainEvents.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("routing keys are hierarchical so a consumer can bind to a family")
    void routingKeysAreHierarchical() {
        // This is the reason for a topic exchange rather than a direct one.
        assertThat(DomainEvents.ORDER_PLACED).startsWith("order.");
        assertThat(DomainEvents.ORDER_CANCELLED).startsWith("order.");
        assertThat(DomainEvents.ORDER_STATUS_CHANGED).startsWith("order.");
        assertThat(DomainEvents.PAYMENT_COMPLETED).startsWith("payment.");
        assertThat(DomainEvents.PAYMENT_FAILED).startsWith("payment.");
    }
}
