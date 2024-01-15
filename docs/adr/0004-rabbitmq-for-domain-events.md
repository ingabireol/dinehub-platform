# ADR 0004 — RabbitMQ for domain events

**Status:** Accepted
**Date:** 2024-01-25

## Context

Placing an order has to trigger payment, which has to trigger the kitchen, which
has to trigger a notification. Those services could call each other directly or
communicate through events.

## Decision

A RabbitMQ topic exchange. Services publish domain events and subscribe to the
ones they care about. The single synchronous call in the system is order-service
asking menu-service to price a basket.

## Options considered

**Synchronous REST between services.** Simplest to trace and to reason about.
Rejected because it couples availability: placing an order would require
order-service, payment-service, kitchen-service and notification-service all to
be up simultaneously. The customer-visible success of an order would then depend
on whether the notification service happened to be restarting. It also makes the
order endpoint as slow as the slowest participant.

**Kafka.** The right choice for high throughput, log replay, or stream
processing. Rejected here as disproportionate: a restaurant does not generate
Kafka volumes, and Kafka is a substantially larger operational commitment —
brokers, partitions, consumer group rebalancing, and a retention policy to reason
about. Worth revisiting if event replay becomes a requirement, because that is
the capability RabbitMQ genuinely lacks.

**RabbitMQ topic exchange.** Chosen. Routing keys are hierarchical
(`order.placed`, `order.cancelled`), so a consumer binds to `order.*` and gets
the family. Dead-letter queues, per-queue retry limits and a management UI come
built in, and the management UI matters more than it sounds — during an incident,
being able to see a queue depth and inspect a stuck message without writing code
is worth a great deal.

## Consequences

**Good.** A service can be down without failing the customer's order; its
messages wait. Adding a consumer requires no change to the publisher. Queue depth
is a direct, visible measure of whether a service is keeping up.

**Bad.** Eventual consistency becomes visible to users: an order shows as
`PLACED` for a moment before becoming `PAID`. The UI has to handle that honestly
rather than pretending the transition is instant.

**The sharp edge.** RabbitMQ guarantees *at-least-once* delivery. Every consumer
will eventually see a duplicate — most often during a redeploy, when
unacknowledged messages are redelivered to the new pod. For `payment.completed`
that means charging twice. Every consumer therefore records processed event ids
in a `processed_events` table with the event id as the primary key, so the
database decides the race rather than an application-level check with a gap in
it. `IdempotencyService` in the shared module implements this, and it is tested
specifically for the concurrent-claim case.

**Failed messages are never silently dropped.** Every queue dead-letters after
its retry budget. The dead-letter queue is alerted on when it is non-empty, which
is the difference between a lost event and a known one.
