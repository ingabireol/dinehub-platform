# Observability

Metrics, alerts, dashboards and logs for the DineHub platform. Everything here
is provisioned from files in this directory — nothing is clicked into existence,
because a dashboard that only exists in somebody's browser is not a dashboard
the team has.

```
make observability-up     # Grafana http://localhost:3000, Prometheus :9090
make observability-down
```

That merges `docker-compose.observability.yml` into the platform's own Compose
project, so the collectors join the `dinehub` network and scrape the services by
name. No second network, no ports published between containers.

## What is here

| Path | What it is |
|------|------------|
| `prometheus/prometheus.yml` | Scrape configuration — static targets for Compose, pod discovery for Kubernetes |
| `prometheus/alerts/platform.yml` | 17 alert rules in six groups |
| `alertmanager/alertmanager.yml` | Routing by severity, with inhibitions so one failure sends one page |
| `grafana/provisioning/` | Datasources and the dashboard provider, both read-only |
| `grafana/dashboards/*.json` | The four dashboards |
| `loki/loki-config.yml` | Single-binary Loki, 72-hour retention |
| `promtail/promtail-config.yml` | Container log collection and JSON parsing |

In Kubernetes the chart creates ServiceMonitors instead of using
`prometheus.yml`, and the same dashboards are mounted from a ConfigMap. The
queries are identical, which is the point: a dashboard that only works in one
environment is a dashboard you cannot trust during an incident.

## The four dashboards

**Service overview** (`dinehub-overview`) — rate, errors and duration for every
service, the slowest and most error-prone endpoints, and the ERROR and WARN
lines from Loki underneath. The first one to open.

**JVM and runtime** (`dinehub-jvm`) — heap, garbage collection, the Hikari pool
and threads. Opened when the overview says a service is slow and the question is
why.

**Business** (`dinehub-business`) — orders, payments, kitchen and notifications.
Every panel here can be wrong while every service is green, which is the reason
it exists.

**Delivery and release health** (`dinehub-delivery`) — did the last release land,
what image is actually running, and are the backups happening. Reads
kube-state-metrics, so it is empty against Compose and populated against a
cluster.

## From a graph to a log line

Every service puts a `traceId` in the MDC (`TraceIdFilter` in `services/common`)
and returns it on error responses. Logback writes it into the JSON log line,
Promtail attaches it as structured metadata, and the Loki datasource turns it
into a link. So:

```
{service="order-service"} | traceId=`3f9c1e02…`
```

returns every line for that one request, across every service that touched it.
A customer with a failed order quotes the id from the error page and the whole
path is one query away. Checked against the local stack: one id returned ten
lines across order-service, payment-service, kitchen-service and
notification-service.

Two log formats have to work for that to be true. In Kubernetes the services log
JSON and Promtail parses it. Under the `local` profile the Compose stack uses,
logback writes colourised text instead so that `docker compose logs` is readable
by a person — so the pipeline strips the colour codes and falls back to a regex
for the same three fields. A stage whose pattern does not match is a no-op, so
the JSON and text paths sit side by side and neither interferes with the other.
Without the fallback, a traceId search against the local stack returns nothing,
and the id is visibly right there in the line — the kind of difference between
environments that is only discovered by the person who needs it most.

## Conventions

These are small decisions, but they are the difference between a dashboard that
can be read under pressure and one that cannot.

**Colour means one thing at a time.** Green, orange and red are reserved for
*state* — a threshold crossed, a budget spent. They are never used to tell one
service from another. Series identity uses Grafana's `palette-classic-by-name`,
which derives the colour from the series name rather than its position, so
`order-service` is the same hue whether it is alone on the panel or one of
seven, and filtering the list does not repaint the survivors.

**Both themes, deliberately.** Panels use Grafana's *named* colours
(`blue`, `orange`, `super-light-blue`) rather than hex values. Named colours are
resolved per theme, so a dashboard that is legible in dark mode is still legible
in light mode — and on the projector in a meeting room, which is where the light
theme actually gets used. Stat tiles colour the *value*, not the panel
background: a flooded tile loses contrast against light-theme text, and the
number is what is being read.

**One y-axis, always.** No panel plots two different units against two scales.
Two measures that do not share a unit are two panels. A dual-axis chart lets any
two lines be made to cross wherever the author wants, which means it tells the
reader nothing.

**Thresholds are the ones in the alert rules.** The orange and red steps on a
gauge are the same numbers as the `warning` and `critical` expressions in
`prometheus/alerts/platform.yml`. If a panel is amber and nothing has paged,
that is a bug in one of the two files.

**Every alert says what to do.** Each rule in `platform.yml` carries an `action`
annotation. An alert that fires at 3am and only describes a symptom has shifted
the work of diagnosis onto the least-rested person available.

**Legends on, labels sparing.** Any panel with more than one series has a legend
with last and max values, so identity never rests on colour alone. Numbers are
not printed on every point.

## Cardinality

Two places where this is actively managed, both of which otherwise end in a
Prometheus or Loki that falls over quietly:

- **Metrics.** `http_server_requests_seconds` carries a `uri` label, and routes
  like `/api/v1/orders/{id}` would produce one time series per order. The scrape
  config drops the histogram buckets for per-id URIs and keeps the aggregate.
- **Logs.** `traceId` and `userId` are structured metadata, not Loki labels. As
  labels they would create a stream per request. As metadata they are still
  queryable and cost nothing. The check is `/loki/api/v1/labels`: it should
  return `container, environment, level, service, service_name, tier` and
  nothing else. A `traceId` in that list means a stage promoted it by mistake.

## Alerting locally

Alertmanager posts to `alert-sink`, a container that echoes what it receives to
stdout, so firing an alert on a laptop produces something readable:

```
docker compose logs -f alert-sink
```

It stands in for Slack or PagerDuty, neither of which belongs in a repository. In
a real deployment the receiver URL is the only line that changes, and it comes
from a secret rather than from this file.
