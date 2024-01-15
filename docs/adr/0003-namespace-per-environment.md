# ADR 0003 — One cluster, a namespace per environment

**Status:** Accepted
**Date:** 2024-02-26
**Revisit:** Before anything resembling real production traffic

## Context

dev, test and prod each need somewhere to run. They could be separate clusters,
separate namespaces in one cluster, or separate node pools.

## Decision

One cluster, three namespaces: `dinehub-dev`, `dinehub-test`, `dinehub-prod`.
Isolation comes from namespaces, NetworkPolicies, ResourceQuotas and RBAC.

## Options considered

**A cluster per environment.** The correct answer for real production, and what
this would be if it were. Rejected here for one honest reason: the project has to
run on a laptop with 16 GB of RAM and on free GitHub runners. Three k3d clusters
is three control planes for a demonstration.

**Namespaces in one cluster.** Chosen. Gives genuine separation of the things
this project is demonstrating — Helm releases, configuration, secrets, network
policy, resource limits, RBAC — at a fraction of the cost.

**One namespace with environment-suffixed names.** Rejected outright. Nothing
separates a dev workload from a prod one except a naming convention, so a
mistyped label can route production traffic to a development pod.

## Consequences

**Good.** Runs on a laptop. The promotion flow, the approval gate, the rollback
behaviour and the per-environment configuration are all real and all
demonstrable. NetworkPolicies and quotas are exercised rather than described.

**Bad, and stated plainly.** A namespace is not a security boundary against a
determined attacker. A node-level compromise, a cluster-wide CRD, or an
etcd-level failure affects all three environments. A noisy dev workload can
exhaust node resources that prod needs — ResourceQuotas limit this but do not
eliminate it. Cluster upgrades affect everything at once, so there is nowhere to
test a Kubernetes upgrade before prod sees it.

**What production would do instead.** A separate cluster for prod, at minimum.
[docs/ENVIRONMENTS.md](../ENVIRONMENTS.md) records this, so the limitation is
documented where someone deciding to use this layout will read it, rather than
only here.
