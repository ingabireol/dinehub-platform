# Environments

Three environments, one cluster, a namespace each. Every difference between them
comes from a Helm values file — the images, the charts and the code are
identical.

---

## The differences

| | dev | test | prod |
| --- | --- | --- | --- |
| Namespace | `dinehub-dev` | `dinehub-test` | `dinehub-prod` |
| Trigger | automatic on merge to `main` | automatic once dev smoke tests pass | **manual approval** |
| Ingress host | `dev.dinehub.local` | `test.dinehub.local` | `dinehub.local` |
| Replicas per service | 1 | 1 | 2, with a PodDisruptionBudget |
| Autoscaling | no | no | HPA on order-service (2–6 pods) |
| Seed data | yes | yes | **no** |
| Swagger UI | on | on | off |
| Log level | DEBUG | INFO | INFO |
| CPU request / limit | 100m / 500m | 100m / 500m | 250m / 1000m |
| Memory request / limit | 384Mi / 512Mi | 384Mi / 512Mi | 768Mi / 1Gi |
| Payment failure simulation | 10% | 10% | 0% |
| DB backup | no | no | nightly `pg_dump` CronJob, 7-day retention |
| Rolling update | default | default | `maxUnavailable: 0` |

---

## Why each difference exists

**Seed data is excluded from production by migration location, not by a flag.**
The `prod` Spring profile sets `spring.flyway.locations` to `classpath:db/migration`
only, leaving out `classpath:db/seed`. The seed SQL therefore cannot run even if
the profile were set wrongly — relying on a boolean to keep published demo
credentials out of a production database is not good enough.

**Swagger is off in production** because an API explorer is a map. It is on in
test because that is where people are actually exploring the API.

**Payment failures are simulated at 10% in dev and test** so that the
cancellation path, the notification and the alert are exercised constantly rather
than being code nobody has ever seen run. They are 0% in production so a
demonstration does not fail at random.

**`maxUnavailable: 0` in production** means a rolling update adds a new pod
before removing an old one. Combined with the PodDisruptionBudget, a node drain
cannot take the last replica of a service.

**HPA on order-service only.** It is the service with the most variable load —
lunch and dinner are real peaks — and the only one where scaling helps. Adding an
HPA to a service that is never the bottleneck adds a moving part for no benefit.

**DB backup in production only** because dev and test are rebuilt from seed data
in minutes. A backup of a disposable environment is an operational cost with no
corresponding risk.

---

## What production would do differently

This runs on one cluster so that the whole thing fits on a laptop and on free CI
runners. That is a real compromise and it is recorded here rather than left
implicit.

| Here | Real production |
| --- | --- |
| One cluster, namespace per environment | A separate cluster for production, at minimum |
| PostgreSQL in-cluster | A managed or dedicated instance with its own backup and PITR |
| One PostgreSQL container, six databases | Separate instances per service, or at least separate hosts |
| RabbitMQ single node in-cluster | A clustered broker with mirrored queues |
| `pg_dump` to a PVC | Snapshots plus WAL archiving to object storage, with restore drills |
| Self-signed TLS at the ingress | Certificates from a real CA, rotated automatically |
| Secrets created from GitHub Secrets at deploy time | An external secrets operator backed by a vault |

**A namespace is not a security boundary against a determined attacker.** A
node-level compromise, a cluster-wide CRD or an etcd failure affects all three
environments. A noisy dev workload can exhaust node resources production needs —
ResourceQuotas limit that but do not eliminate it. And a Kubernetes upgrade
affects everything at once, so there is nowhere to test it first.

See [ADR 0003](adr/0003-namespace-per-environment.md).

---

## Adding an environment

Say you want `staging`.

1. **Create the values file.** Copy the nearest existing one:
   ```bash
   cp deploy/helm/values-test.yaml deploy/helm/values-staging.yaml
   ```
   Change the ingress host, the replica counts and anything else that genuinely
   differs. Resist adding configuration that does not differ — a values file that
   repeats the chart defaults makes it impossible to see what is special about
   the environment.

2. **Create the namespace and its secrets.**
   ```bash
   kubectl create namespace dinehub-staging
   kubectl create secret generic dinehub-db \
     --namespace dinehub-staging \
     --from-literal=password="${DB_PASSWORD}"
   kubectl create secret generic dinehub-jwt \
     --namespace dinehub-staging \
     --from-literal=secret="${JWT_SECRET}"
   ```

3. **Add a GitHub Environment** named `staging`, with its own `KUBECONFIG_STAGING`
   secret and whatever protection rules it needs.

4. **Add the deploy job** to `.github/workflows/cd.yml`. It is a copy of the
   `deploy-test` job with the environment name and values file changed — the
   deployment logic itself is a reusable workflow, so there is no job body to
   duplicate.

5. **Say where it sits in the promotion chain.** A new environment that nothing
   gates on, and that gates on nothing, is a deployment target rather than an
   environment.

---

## Local

`make up` runs everything in Docker Compose: seven services, PostgreSQL,
RabbitMQ and Redis. It is not a fourth environment — there is no Helm chart and
no Kubernetes involved — but it uses the same images and the same configuration
mechanism, so a service that works here works there.

The one structural difference is the single PostgreSQL container hosting six
databases. The boundary is still real (separate databases, separate credentials,
no cross-database queries are possible), but it is a shared failure domain that
production would not have.
