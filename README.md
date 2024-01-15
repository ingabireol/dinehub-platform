# DineHub Platform

> **Portfolio project. All data, hostnames and credentials are fictitious.**

A restaurant ordering platform built as microservices, with a complete CI/CD
pipeline that builds, tests, scans and deploys to **dev**, **test** and **prod**.

The application is deliberately modest. The delivery and operations around it
are the point: build-once-deploy-many, environment promotion with an approval
gate, automatic rollback on a failed health check, infrastructure as code,
observability, and security scanning at every stage.

[![CI](https://github.com/olivier-ingabire/dinehub-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/olivier-ingabire/dinehub-platform/actions/workflows/ci.yml)
[![CD](https://github.com/olivier-ingabire/dinehub-platform/actions/workflows/cd.yml/badge.svg)](https://github.com/olivier-ingabire/dinehub-platform/actions/workflows/cd.yml)
[![Security](https://github.com/olivier-ingabire/dinehub-platform/actions/workflows/security-nightly.yml/badge.svg)](https://github.com/olivier-ingabire/dinehub-platform/actions/workflows/security-nightly.yml)

---

## Quick start

Requires Docker. Everything else — Maven, the JDK, Node — runs in containers.

```bash
make up
```

Then open <http://localhost>. The menu is seeded and these accounts exist:

| Email | Role | Password |
| --- | --- | --- |
| `customer@dinehub.local` | CUSTOMER | `DineHub2024!` |
| `chef@dinehub.local` | KITCHEN | `DineHub2024!` |
| `admin@dinehub.local` | ADMIN | `DineHub2024!` |

These are seeded only under the `local`, `dev` and `test` profiles. The `prod`
profile excludes the seed migrations entirely — which is why publishing the
password here is safe.

```bash
make test            # unit + integration tests, with the coverage gate
make cluster         # local k3d cluster with dev/test/prod namespaces
make deploy ENV=dev  # helm install into dinehub-dev
make smoke ENV=dev   # end-to-end checks against that environment
make help            # everything else
```

---

## Architecture

```mermaid
graph TB
    subgraph client["Client"]
        WEB["Angular SPA<br/>served by Nginx"]
    end

    subgraph edge["Edge"]
        GW["api-gateway<br/>Spring Cloud Gateway<br/>routing · JWT · CORS · rate limit"]
    end

    subgraph services["Services"]
        AUTH["auth-service<br/>users"]
        MENU["menu-service<br/>menu"]
        ORDER["order-service<br/>orders"]
        PAY["payment-service<br/>payments"]
        KIT["kitchen-service<br/>tickets"]
        NOTIF["notification-service<br/>notifications"]
    end

    subgraph data["Data"]
        PG[("PostgreSQL 16<br/>one database per service")]
        MQ{{"RabbitMQ<br/>topic exchange"}}
    end

    WEB -->|HTTPS| GW
    GW --> AUTH & MENU & ORDER & PAY & KIT & NOTIF

    AUTH & MENU & ORDER & PAY & KIT & NOTIF --> PG
    AUTH & MENU & ORDER & PAY & KIT & NOTIF -.->|publish / consume| MQ

    ORDER -->|price snapshot| MENU
```

**No service discovery server.** Kubernetes Services already provide DNS, health
checking and load balancing; Docker Compose provides the same by container name.
Running Eureka alongside either adds a component to operate for no benefit —
see [ADR 0001](docs/adr/0001-kubernetes-services-over-eureka.md).

**Services never call each other to read state.** They publish events. The one
synchronous call is order-service asking menu-service to price a basket, because
an order must be priced at the moment it is placed and cannot wait for an event.

Detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## The pipeline

```mermaid
graph LR
    PR["Pull request"] --> CI
    subgraph CI["ci.yml — every PR"]
        direction TB
        C1["changed services only"] --> C2["build + unit test"]
        C2 --> C3["integration tests<br/>Testcontainers"]
        C3 --> C4["coverage gate 70%"]
        C4 --> C5["CodeQL · gitleaks<br/>dependency review"]
        C5 --> C6["build images<br/>Trivy scan"]
        C6 --> C7["helm lint · kubeconform<br/>Trivy config"]
        C7 --> C8["deploy to a kind cluster<br/>run smoke tests"]
    end

    CI --> MERGE["merge to main"]
    MERGE --> CD

    subgraph CD["cd.yml — build once, deploy many"]
        direction TB
        D1["version + build images<br/>push to GHCR + SBOM"] --> D2["deploy dev"]
        D2 --> D3["smoke dev"]
        D3 --> D4["deploy test"]
        D4 --> D5["smoke + API e2e"]
        D5 --> GATE{{"manual approval"}}
        GATE --> D6["deploy prod"]
        D6 --> D7["smoke prod"]
        D7 --> D8["GitHub Release<br/>digests + SBOM"]
    end

    D3 -.->|fail| RB1["helm rollback<br/>pipeline fails"]
    D7 -.->|fail| RB2["helm rollback<br/>alert"]
```

The same image digest is deployed to all three environments. The job summary and
the release notes both list the digests, so "is prod running what test
approved?" is answered by reading, not by trusting.

Detail, including how this runs without a permanent cluster:
[docs/PIPELINE.md](docs/PIPELINE.md).

---

## Repository layout

```
services/            Spring Boot services, one module each, plus a small shared library
web/                 Angular app, served by Nginx
deploy/
  docker-compose.yml Full local stack
  helm/              Umbrella chart + one values file per environment
  k8s/bootstrap/     Namespaces, ingress, platform components
  scripts/           Cluster creation, smoke tests, rollback
observability/       Prometheus rules, Grafana dashboards, Loki
.github/workflows/   ci.yml, cd.yml, security-nightly.yml
docs/                Architecture, pipeline, environments, runbooks, ADRs
```

---

## Environments

| | dev | test | prod |
| --- | --- | --- | --- |
| Trigger | automatic on merge | automatic after dev smoke passes | manual approval |
| Replicas | 1 | 1 | 2 + PodDisruptionBudget |
| Seed data | yes | yes | **no** |
| Swagger UI | on | on | off |
| HPA | no | no | yes, on order-service |
| DB backup | no | no | nightly CronJob, 7-day retention |

Every difference comes from a Helm values file. The images, the charts and the
code are identical. [docs/ENVIRONMENTS.md](docs/ENVIRONMENTS.md).

---

## Documentation

| Document | What it covers |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Services, data ownership, event flow, the choices that were contested |
| [PIPELINE.md](docs/PIPELINE.md) | Every stage, the quality gates, Modes A and B, GitHub setup |
| [ENVIRONMENTS.md](docs/ENVIRONMENTS.md) | What differs per environment and how to add one |
| [RELEASE_RUNBOOK.md](docs/RELEASE_RUNBOOK.md) | Releasing to production, with pre- and post-checks |
| [ROLLBACK_RUNBOOK.md](docs/ROLLBACK_RUNBOOK.md) | Automatic and manual rollback, and database migrations |
| [INCIDENT_RUNBOOK.md](docs/INCIDENT_RUNBOOK.md) | Triage using the dashboards and logs |
| [SECURITY.md](SECURITY.md) | How secrets are handled in each environment |
| [adr/](docs/adr/) | Why Kubernetes Services over Eureka, build-once, namespaces, RabbitMQ |

---

## Screenshots

<!-- Replace these placeholders once the pipeline has run against your fork. -->

| | |
| --- | --- |
| Pipeline run | `docs/images/pipeline-run.png` |
| Grafana service overview | `docs/images/grafana-overview.png` |
| Grafana business metrics | `docs/images/grafana-business.png` |
| The application | `docs/images/app-order-flow.png` |

---

## Licence

[MIT](LICENSE).
