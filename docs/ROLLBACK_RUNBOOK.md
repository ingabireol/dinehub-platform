# Runbook: Rolling back

**Roll back first, diagnose afterwards.** The evidence — logs, metrics,
dead-lettered messages — survives a rollback. The outage does not have to.

---

## Automatic rollback

Two mechanisms, and they cover different failures.

**Helm's `--atomic`.** If a release does not become healthy within the timeout,
Helm reverts it itself. This catches a pod that never becomes Ready: a bad image,
a failing probe, a missing secret.

**The pipeline's smoke test.** After every deploy, `smoke-test.sh` runs against
the environment. On failure the job runs `helm rollback` and then fails, so the
pipeline is red *and* the environment is serving the previous release. Both
matter — a green pipeline over a broken environment is the worst outcome.

This catches the case `--atomic` cannot: every pod is Ready and the platform
still does not work. A service that starts fine but cannot reach its database
looks healthy to Kubernetes and fails every order.

Neither needs you to do anything. If you see them fire, go to
[After a rollback](#after-a-rollback).

---

## Manual rollback

When something was missed and production needs to go back.

```bash
# What is deployed, and what came before
./deploy/scripts/rollback.sh --env prod --list

# Back one release
./deploy/scripts/rollback.sh --env prod

# Back to a specific revision
./deploy/scripts/rollback.sh --env prod --revision 7
```

The script prompts for confirmation on production. The pipeline passes `--force`
because it has already made the decision.

### Or directly

```bash
helm history dinehub --namespace dinehub-prod
helm rollback dinehub --namespace dinehub-prod --wait --timeout 10m
kubectl rollout status deployment --namespace dinehub-prod
./deploy/scripts/smoke-test.sh --env prod
```

---

## Deciding whether to roll back

You usually have minutes, not hours. The decision is simpler than it feels:

| Situation | Decision |
| --- | --- |
| Error rate up, started at the deployment annotation | **Roll back.** Diagnose from the logs afterwards. |
| One service broken, the rest fine | **Roll back the whole release.** A partially rolled-back estate is harder to reason about than either state. |
| Latency up but no errors | **Watch for five minutes.** A JIT warming up looks like this and resolves. If it does not, roll back. |
| A bug that predates this release | **Do not roll back.** You would be removing a fix for a problem you already had. Fix forward. |
| A destructive migration already ran | **Stop.** Read [Migrations](#migrations) before touching anything. |

When genuinely unsure, roll back. The cost of an unnecessary rollback is one
re-release; the cost of a late one is measured in the outage.

---

## Migrations

This is where rollback gets dangerous, and it is worth being blunt: **rolling
back code does not roll back the schema.**

### Why there is no automatic down-migration

A down-migration that drops a column destroys data that the forward migration
created. Running one automatically during an incident, under time pressure, with
nobody reviewing it, is a way to turn an outage into data loss. Flyway supports
undo scripts; this project deliberately does not use them.

The protection is the discipline in
[RELEASE_RUNBOOK.md](RELEASE_RUNBOOK.md#migrations): **every migration must be
backward compatible with the release before it.** Where that discipline held,
rolling back code against the new schema is safe, because the previous code was
written to work with both.

### When it did not hold

Work out what the migration did:

```bash
kubectl exec -it deploy/dinehub-order-service --namespace dinehub-prod -- \
  sh -c 'psql "$DATABASE_URL" -c "SELECT version, description, installed_on, success \
         FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"'
```

| The migration | What to do |
| --- | --- |
| Added a nullable column or a table | **Roll back the code.** Old code ignores what it does not know about. Leave the schema; drop it later, deliberately. |
| Added an index | **Roll back the code.** An index is invisible to the application. |
| Dropped or renamed a column | **Do not roll back.** The old code will query a column that no longer exists and fail on every request. Fix forward. |
| Changed a column's type | **Do not roll back.** Same reason. |
| Added `NOT NULL` or a unique constraint | **Probably fix forward.** The old code may write rows the constraint now rejects. |

### If you genuinely must go back through a destructive migration

This is a data-loss event and should be treated as one. Declare it, get a second
person, and work from
[INCIDENT_RUNBOOK.md](INCIDENT_RUNBOOK.md#restoring-from-backup). Restoring the
database loses every transaction since the backup, so the question is which loss
is smaller — and that is a business decision, not an engineering one.

---

## After a rollback

The environment is working again. The incident is not over.

- [ ] **Confirm it is actually fixed**, from outside:
      `./deploy/scripts/smoke-test.sh --env prod`
- [ ] **Check the dead-letter queue.** Events rejected while the bad release was
      running are still there, and some of them represent real orders.
      ```bash
      kubectl port-forward svc/dinehub-rabbitmq 15672:15672 --namespace dinehub-prod
      # then http://localhost:15672 → Queues → dinehub.dlq
      ```
- [ ] **Look for stuck orders.** The `dinehub_orders_stuck` gauge shows orders
      that stopped moving during the outage. They will not recover by themselves.
- [ ] **Capture the evidence before it rotates**: the logs from the failed
      release, the Grafana window, the smoke test output.
- [ ] **Stop the bad release being redeployed.** A rollback changes the cluster,
      not `main`. The next merge will deploy the same broken code unless the
      commit is reverted:
      ```bash
      git revert <sha>
      ```
- [ ] **Write down what happened** while it is fresh. The version written the
      next morning is always worse.

---

## Rolling back the pipeline itself

A broken workflow is less dramatic and more confusing, because nothing deploys
and nothing explains why.

```bash
git revert <sha-of-the-workflow-change>
git push
```

GitHub always runs the workflow from the commit being tested, so reverting is
enough — there is no cached version to clear.

---

## What rollback does not fix

Said plainly, because discovering it mid-incident is expensive:

- **Data already written by the bad release.** Rolling back the code does not
  remove rows it created or un-send notifications it sent.
- **Events already published.** A consumer that acted on them has acted.
- **Payments already taken.** Those are a refund conversation, not a deployment
  one.
- **Anything a user already saw.** An incorrect price shown for ten minutes was
  shown.

Rollback restores the service. Reconciling what happened while it was broken is
separate work, and it is usually the larger half.
