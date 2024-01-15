# ADR 0001 — Kubernetes Services instead of a discovery server

**Status:** Accepted
**Date:** 2024-01-12

## Context

Services need to find each other. The Spring Cloud convention is a discovery
server — Eureka or Consul — that services register with and query.

## Decision

No discovery server. Services address each other by Kubernetes Service name in
the cluster, and by container name under Docker Compose. Service URLs are
environment variables with local defaults.

## Options considered

**Eureka.** The default in most Spring Cloud tutorials, and what a reviewer may
expect to see. Rejected because Kubernetes already provides everything Eureka
does, and better:

| Need | Eureka | Kubernetes |
| --- | --- | --- |
| Name resolution | Client-side registry lookup | Cluster DNS |
| Health checking | Heartbeat, 30s default eviction | Readiness probe, seconds |
| Load balancing | Client-side (Ribbon/LoadBalancer) | kube-proxy or the Service mesh |
| Removing a dead instance | After the eviction timeout | As soon as readiness fails |

Running Eureka means a highly available pair of servers to operate, patch,
monitor and recover, plus a registration client in every service, plus a new
failure mode — a service that is healthy but has fallen out of the registry.
None of that buys anything Kubernetes was not already doing.

Eureka's eviction delay is the sharper point. A pod that dies is removed from a
Kubernetes Service's endpoints almost immediately; Eureka's default leaves it in
the registry for up to 90 seconds, during which clients keep routing to it. That
turns a pod restart into a minute of errors.

**Consul.** Stronger than Eureka and useful outside Kubernetes. Same objection:
a component to operate for a capability already present.

**Spring Cloud Kubernetes discovery.** Reads Services through the Kubernetes API
instead of DNS. Rejected as unnecessary indirection — it needs RBAC to list
endpoints, and DNS already works without any permissions at all.

## Consequences

**Good.** One fewer component to run, patch and recover. No registration client
in any service. Failover is as fast as the readiness probe. The same mechanism
works under Docker Compose, so local and deployed behave alike.

**Bad.** The service URLs are configuration rather than discovery, so adding a
service means adding a value. That is a one-line change in a values file, and it
has the advantage of being reviewable — an unexpected service cannot become
reachable simply by starting up and registering itself.

**Not a lock-in.** If this ever ran somewhere without Kubernetes, the services
address each other by URL from configuration, so the change is a values file,
not a code change.
