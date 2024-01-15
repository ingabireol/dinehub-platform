# ADR 0005 — A database per service

**Status:** Accepted
**Date:** 2024-01-25

## Context

Six services need to persist data. They could share one database, share one
instance with separate schemas, or each own a separate database.

## Decision

One logical database per service, with its own credential and its own Flyway
history. No service reads another's tables. Locally these are databases inside a
single PostgreSQL container; in Kubernetes they are separate databases, and in
real production they would be separate instances.

## Options considered

**A shared database.** Simplest to run and allows joins across domains.
Rejected: a shared schema makes every service's migration everybody's problem,
and the first time one service reads another's table, the boundary is gone and
the services are a distributed monolith with extra network hops. That coupling is
invisible until a schema change breaks a service nobody thought was involved.

**One instance, schema per service.** A reasonable middle ground. Rejected
mainly because it makes cross-schema queries easy, and anything easy gets done
under deadline pressure. The boundary needs to be enforced by credentials, not by
convention.

**A database per service.** Chosen. Each service has a credential that can reach
only its own database, so a cross-domain query fails with a permission error at
development time rather than succeeding and creating coupling.

## Consequences

**Good.** A service can change its schema without coordinating with anyone. A
compromised service reaches one database. Flyway histories are independent, so a
failed migration blocks one service rather than the deployment.

**Bad.** No cross-domain joins and no cross-domain transactions. Data that logically
belongs together is duplicated — order-service snapshots the item name and price
onto the order rather than joining to menu-service.

**That duplication is deliberate, not a workaround.** An order must record what
the customer was actually charged at the moment they ordered. Joining to the live
menu would mean a later price change retroactively altered historic orders, which
is both wrong and, for anything with a financial record, unacceptable. The
snapshot is the correct model even without the service boundary.

**Local compromise, recorded.** `make up` runs one PostgreSQL container hosting
six databases, because asking a laptop to run six Postgres instances is not
reasonable. The boundary is still real — separate databases, separate
credentials, no cross-database queries are possible — but the single instance is
a shared failure domain that production would not have.
[docs/ENVIRONMENTS.md](../ENVIRONMENTS.md) says so.
