# Runbook: Incident response

**Read this when something is broken and you do not yet know what.**

---

## First five minutes

Do these in order. Establishing *scope* first is worth more than it feels,
because one user, one service and everything each point at a different place.

```bash
# 1. Is the platform up from outside itself?
curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' https://dinehub.local/healthz

# 2. What does it think of itself?
./deploy/scripts/smoke-test.sh --env prod

# 3. What is Kubernetes saying?
kubectl get pods --namespace dinehub-prod
kubectl get events --namespace dinehub-prod --sort-by=.lastTimestamp | tail -20

# 4. What changed?
helm history dinehub --namespace dinehub-prod --max 5
```

**If anything deployed in the last hour, that is your first hypothesis.** The
Grafana deployment annotation lines up with the metrics; a change *before* the
annotation is not your release.

---

## Triage by symptom

| Symptom | Likely cause | Section |
| --- | --- | --- |
| Nothing responds at all | Ingress, or the gateway | [Platform down](#platform-down) |
| 5xx from one service | That service | [One service failing](#one-service-failing) |
| Orders stay PLACED forever | The event chain | [Orders not progressing](#orders-not-progressing) |
| Everything slow, no errors | Database, or JVM | [Slow](#slow-but-not-down) |
| Dead-letter queue filling | A poison message, or a contract mismatch | [Dead letters](#dead-letter-queue) |
| Payments all failing | Configuration | [Payments failing](#payments-failing) |

---

## Platform down

```bash
kubectl get pods --namespace dinehub-prod -l app.kubernetes.io/name=api-gateway
kubectl logs deploy/dinehub-api-gateway --namespace dinehub-prod --tail=100
kubectl get ingress --namespace dinehub-prod
```

| What you see | What it means |
| --- | --- |
| Gateway pods `CrashLoopBackOff` | It cannot start. Almost always a missing or malformed `JWT_SECRET` — the service refuses to start without one by design |
| Gateway Ready, nothing responds | The ingress is not routing. Check the ingress controller and the host rule |
| `ImagePullBackOff` | The tag does not exist in GHCR, or the pull secret is wrong |
| Pods pending | No node capacity. `kubectl describe pod` names the unsatisfied request |

The gateway holds no data, so restarting it is cheap and safe:

```bash
kubectl rollout restart deployment/dinehub-api-gateway --namespace dinehub-prod
```

---

## One service failing

```bash
SVC=order-service   # or whichever

kubectl logs "deploy/dinehub-${SVC}" --namespace dinehub-prod --tail=200 \
  | grep -iE '"level":"(ERROR|WARN)"'

kubectl describe "deploy/dinehub-${SVC}" --namespace dinehub-prod
kubectl top pods --namespace dinehub-prod | grep "$SVC"
```

**Logs are JSON.** To follow a single request across services, take the trace id
from the user's error message and query Loki:

```
{namespace="dinehub-prod"} | json | traceId="a3f91c2b4d5e6f70"
```

That one query returns everything that happened to that request, across every
service and every queue hop. It is the single most useful thing in this runbook.

| Observation | Cause |
| --- | --- |
| Readiness failing, liveness passing | A dependency is down — the probe is doing its job and the pod is correctly out of the load balancer |
| `OOMKilled` in `kubectl describe` | The heap plus native memory exceeds the container limit. Raise the limit, or lower `MaxRAMPercentage` |
| `Connection refused` to Postgres | The database is down, or a NetworkPolicy is blocking it |
| `No qualifying bean` at startup | Wiring, not runtime. Almost certainly a recently added component the scanning configuration cannot see |

---

## Orders not progressing

Orders stay `PLACED`. Nothing is throwing an error — that is what makes this
hard, and it is exactly the case the stuck-order sweep exists for.

```bash
# How many, and for how long
kubectl exec deploy/dinehub-order-service --namespace dinehub-prod -- \
  curl -s localhost:8083/actuator/prometheus | grep dinehub_orders_stuck
```

Work along the chain:

```bash
# 1. Is order-service publishing?
kubectl logs deploy/dinehub-order-service --namespace dinehub-prod --tail=100 \
  | grep -i 'order.placed'

# 2. Is RabbitMQ holding the messages?
kubectl port-forward svc/dinehub-rabbitmq 15672:15672 --namespace dinehub-prod
# http://localhost:15672 → Queues. A depth that only rises means nothing is consuming.

# 3. Is payment-service consuming?
kubectl logs deploy/dinehub-payment-service --namespace dinehub-prod --tail=100

# 4. Is anything dead-lettered?
# RabbitMQ UI → Queues → dinehub.dlq
```

| Finding | Cause |
| --- | --- |
| Queue depth rising, consumers 0 | payment-service is down or cannot connect to the broker |
| Queue depth rising, consumers > 0 | The consumer is failing and retrying. Its logs say why |
| Queue empty, orders still PLACED | The event was published to a routing key nothing is bound to — check a recent change to a queue or binding |
| Nothing in the queue and nothing in the DLQ | The event was lost. See [Replaying](#replaying-a-lost-event) |

---

## Dead-letter queue

A message in the DLQ failed three attempts. It is not lost, and nothing deletes
it — but nothing retries it either.

```bash
kubectl port-forward svc/dinehub-rabbitmq 15672:15672 --namespace dinehub-prod
# UI → Queues → dinehub.dlq → Get messages (ack mode: requeue)
```

Read one. The payload says which event and which order.

| Pattern | Cause | Action |
| --- | --- | --- |
| All from one consumer, all the same error | A bug in that consumer | Fix it, then shovel the queue back |
| `unknown status: …` | Two services on different versions disagree about an enum | Finish the deployment, then shovel back |
| Deserialisation failures | An event shape changed incompatibly | The messages cannot be processed by the current code. Decide deliberately whether to transform or discard them |
| A handful, mixed | Transient failures that exhausted their retries | Shovel back once the cause is gone |

To replay, use the RabbitMQ shovel plugin or re-publish from the UI. **Fix the
cause first** — replaying into a broken consumer just refills the DLQ and
destroys the evidence of how many there originally were.

---

## Replaying a lost event

An order is stuck, nothing is in any queue, and nothing is in the DLQ. The event
was genuinely lost.

**Do not re-drive a payment automatically.** Work out what actually happened:

```bash
# Did payment-service ever see it?
kubectl exec deploy/dinehub-payment-service --namespace dinehub-prod -- \
  sh -c 'psql "$DATABASE_URL" -c "SELECT * FROM payments WHERE order_id = '\''<id>'\'';"'
```

| Result | What to do |
| --- | --- |
| A completed payment exists | The customer paid; the result event was lost. Re-publish `payment.completed` — order-service is idempotent and will apply it once |
| A failed payment exists | Re-publish `payment.failed` so the order cancels |
| No payment row at all | `order.placed` never arrived. Re-publish it; payment-service's unique constraint on `order_id` means a duplicate cannot double-charge |

---

## Payments failing

```bash
kubectl exec deploy/dinehub-payment-service --namespace dinehub-prod -- \
  curl -s localhost:8084/actuator/prometheus | grep dinehub_payments
```

**If every payment is failing**, check the configuration first:

```bash
kubectl get deploy/dinehub-payment-service --namespace dinehub-prod \
  -o jsonpath='{.spec.template.spec.containers[0].env}' | tr ',' '\n' | grep -i failure
```

`PAYMENT_FAILURE_RATE` should be `0.0` in production. A value of `10` instead of
`0.10` declines everything — which is why `PaymentGateway` refuses to start on a
value outside 0–1, and why seeing it start at all narrows the problem.

---

## Slow but not down

Work down the stack. Each step rules out a layer:

```bash
# 1. Is the JVM collecting instead of serving?
#    Grafana → JVM → GC time. Above 10% is your answer.

# 2. Are threads waiting for a database connection?
#    Grafana → Connection pool. Any sustained "pending" is your answer.

# 3. Is the database slow?
kubectl exec deploy/dinehub-postgres --namespace dinehub-prod -- \
  psql -U dinehub -c "SELECT pid, now()-query_start AS duration, state, left(query,80) \
                      FROM pg_stat_activity WHERE state <> 'idle' \
                      ORDER BY duration DESC LIMIT 10;"

# 4. Is the broker backed up?
#    RabbitMQ UI → Overview → message rates.
```

The order matters: a slow database produces pool exhaustion produces high
latency, so fixing the pool would be treating the symptom.

---

## Restoring from backup

**Last resort.** Restoring loses every transaction since the backup was taken.

```bash
# What is available
kubectl exec deploy/dinehub-postgres --namespace dinehub-prod -- ls -la /backups

# Stop the writers first. A partially restored database taking new orders is
# worse than a down one.
kubectl scale deployment --namespace dinehub-prod --replicas=0 \
  dinehub-order-service dinehub-payment-service dinehub-kitchen-service

# Restore
kubectl exec -i deploy/dinehub-postgres --namespace dinehub-prod -- \
  psql -U dinehub -d dinehub_orders < /backups/dinehub_orders-<date>.sql

# Bring them back
kubectl scale deployment --namespace dinehub-prod --replicas=2 \
  dinehub-order-service dinehub-payment-service dinehub-kitchen-service
```

Then reconcile: orders placed after the backup are gone from the database but
their payments may have been taken. That is a manual, business-level exercise and
there is no shortcut.

---

## Closing an incident

- [ ] **Confirm recovery from outside**, not just from a health endpoint.
- [ ] **Check the dead-letter queue is empty.**
- [ ] **Check the stuck-order gauge is zero.**
- [ ] **Preserve the evidence** — logs rotate, the Grafana window scrolls away.
- [ ] **Check nothing is left in a temporary state**: a scaled-down deployment, a
      disabled probe, a patched image tag.
      ```bash
      helm get values dinehub --namespace dinehub-prod
      kubectl get deploy --namespace dinehub-prod
      ```
- [ ] **Write it down while it is fresh.** What was observed, what was tried,
      what worked. The version written the next morning is always worse.

The review afterwards asks what made the fault hard to find, not who caused it.
The first question produces improvements; the second produces silence.
