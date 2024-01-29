# Runbook: Releasing to production

Written for someone doing this for the first time, under mild time pressure.

**Normal path: approve the pipeline's prod job and watch.** Everything below is
the detail behind that, and what to do when it is not normal.

---

## Before you approve

The pipeline has already deployed this exact image digest to dev and test and run
smoke tests against both. The approval is a judgement about *timing and
blast radius*, not about whether the code works.

- [ ] **The test environment is actually green**, not merely deployed. Check the
      smoke test output in the job summary, not just the green tick on the job.
- [ ] **Read the commit list** in the job summary. If it contains something you
      did not expect to be releasing, stop and find out why.
- [ ] **Check for database migrations.** `git diff --stat <last-prod-tag>..HEAD -- '*/db/migration/'`.
      If there are any, read [Migrations](#migrations) below before continuing.
- [ ] **Is anyone else deploying?** Concurrency groups prevent two deploys to the
      same namespace, but not a deploy during someone else's incident.
- [ ] **Is there time to watch it?** A release approved five minutes before you
      leave is a release nobody is watching.

---

## Approving

GitHub → Actions → the running `cd.yml` → the `deploy-prod` job → **Review
deployments** → approve.

The job then:

1. Creates or updates the Kubernetes Secrets from GitHub Secrets.
2. `helm upgrade --install --atomic --wait --timeout 10m` with
   `values-prod.yaml` and the exact image tags built at the start of the run.
3. Runs `smoke-test.sh --env prod`.
4. On smoke failure: `helm rollback`, then fails the job.
5. On success: creates the GitHub Release with the digests and SBOM links.

`--atomic` means Helm rolls the release back itself if it does not become healthy
within the timeout. You do not have to do anything for that case.

---

## Watching it

```bash
# The rollout, service by service
kubectl rollout status deployment --namespace dinehub-prod --timeout=5m

# Pods, as they cycle
kubectl get pods --namespace dinehub-prod --watch

# Anything restarting is the first thing to notice
kubectl get pods --namespace dinehub-prod \
  --sort-by='.status.containerStatuses[0].restartCount'
```

With `maxUnavailable: 0`, a new pod becomes Ready before an old one is removed.
A service that never reaches Ready will therefore hang the rollout rather than
taking the old pods away — which is the correct failure mode, and why the
`--timeout` matters.

**In Grafana, watch for ten minutes after the rollout completes:**

| Panel | What a bad release looks like |
| --- | --- |
| Error rate per service | A step change at the deployment annotation |
| p95 latency | A step up that does not settle within a few minutes |
| JVM heap | A new sawtooth that does not come back down |
| RabbitMQ queue depth | A queue that starts growing and does not drain |
| Dead-letter queue | Anything at all — it should be empty |

The deployment annotation on the dashboards is the thing to line changes up
against. A metric that changed *before* the annotation is not your release.

---

## Verifying

```bash
# The version actually running
kubectl get deployment --namespace dinehub-prod \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'

# Smoke test against the real ingress
./deploy/scripts/smoke-test.sh --env prod
```

Then do one thing by hand that a smoke test cannot: place an order through the
web UI and watch it reach the kitchen board. Automated checks confirm the API
works; a human confirms the product does.

---

## Migrations

This is where releases go wrong.

**The rule: every migration must be backward compatible with the release before
it.** Not "compatible enough" — compatible. That constraint is what makes
rollback possible at all, and it costs one extra release to satisfy.

### Expand and contract, over three releases

| Release | Schema | Code |
| --- | --- | --- |
| N | Add the new column, nullable. Backfill. | Write to both; read from the old. |
| N+1 | — | Read from the new; still write to both. |
| N+2 | Drop the old column. | Write to the new only. |

Any release can be rolled back to its predecessor, because the schema at each
step supports both code versions.

### What this means in practice

**Safe in a single release:** adding a nullable column, adding a table, adding
an index (use `CREATE INDEX CONCURRENTLY` on a large table), adding a
non-breaking CHECK.

**Needs the three-release dance:** renaming a column, dropping a column,
changing a type, adding `NOT NULL` to an existing column, adding a unique
constraint to a column with existing data.

**Before approving a release with a destructive migration**, confirm there is a
backup from *after* the last successful release and *before* this one:

```bash
kubectl get cronjob dinehub-db-backup --namespace dinehub-prod
kubectl get jobs --namespace dinehub-prod --sort-by=.metadata.creationTimestamp | tail -3
```

If the nightly backup has not run since the last release, trigger one and wait
for it before approving:

```bash
kubectl create job --from=cronjob/dinehub-db-backup \
  "pre-release-$(date +%Y%m%d%H%M)" --namespace dinehub-prod
```

---

## Rotating the JWT signing key

Rotating it invalidates every issued token, so every user is logged out. Done
carelessly that is a visible outage; done in two steps it is not.

The services validate with a single key, so a genuinely seamless rotation needs
a key-id claim and a two-key validation window — which this project does not
implement. What it supports instead is a *low-impact* rotation:

1. Pick a quiet window.
2. Update the secret and restart:
   ```bash
   kubectl create secret generic dinehub-jwt \
     --namespace dinehub-prod \
     --from-literal=secret="${NEW_JWT_SECRET}" \
     --dry-run=client -o yaml | kubectl apply -f -

   kubectl rollout restart deployment --namespace dinehub-prod
   kubectl rollout status deployment --namespace dinehub-prod
   ```
3. Expect a spike in 401s as clients retry, then recovery as users log in again.
   The Angular client handles a 401 by redirecting to login, so the user
   experience is a re-login, not an error page.
4. Update the GitHub Secret so the next deploy does not revert it.

Recorded honestly: this is a limitation. Supporting overlapping keys would mean a
`kid` header and a key set, and it is worth doing before anything resembling real
production traffic.

---

## When something is wrong

| Symptom | Do this |
| --- | --- |
| Pods will not become Ready | `kubectl describe pod` — usually a probe timeout or a missing secret |
| Rollout times out | Helm `--atomic` is already rolling back; let it finish, then diagnose |
| Smoke tests fail | The pipeline rolls back automatically. [ROLLBACK_RUNBOOK.md](ROLLBACK_RUNBOOK.md) |
| Error rate rises after a green deploy | [ROLLBACK_RUNBOOK.md](ROLLBACK_RUNBOOK.md) — decide quickly, roll back, diagnose afterwards |
| Only one service is broken | Still roll back the whole release; a partially rolled-back estate is harder to reason about |
| Dead-letter queue filling | [INCIDENT_RUNBOOK.md](INCIDENT_RUNBOOK.md) — events are being rejected, orders are stalling |

**Roll back first, diagnose afterwards.** The evidence — logs, metrics, the
dead-lettered messages — survives a rollback. The outage does not have to.

---

## After the release

- [ ] The GitHub Release exists, with digests and the changelog.
- [ ] Grafana is clean after ten minutes.
- [ ] The dead-letter queue is empty.
- [ ] Anything learned that is not written down anywhere gets added to a runbook
      — including this one.
