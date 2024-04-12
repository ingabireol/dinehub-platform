# ADR 0006 — maxUnavailable: 0, because `helm --wait` depends on it

**Status:** Accepted
**Date:** 2024-04-06

## Context

The deployment jobs run `helm upgrade --atomic --wait`, and the pipeline's
rollback story rests entirely on that: if the new pods never become Ready, the
upgrade is supposed to fail and `--atomic` is supposed to put the previous
release back.

That claim was tested rather than assumed. A release was deployed to dev with
the readiness probe pointed at `/actuator/health/this-endpoint-does-not-exist`,
a path that cannot return 200. Helm printed `Upgrade complete` after three
seconds, the release was recorded as `deployed`, and the environment sat with
every service at `0/1 Ready` for eleven hours. The safety net was not there.

The cause is arithmetic rather than a bug. Helm's readiness check for a
Deployment is

```
readyReplicas >= replicas - maxUnavailable
```

The chart ran one replica per service with `maxUnavailable: 1`, so the bar was
`1 - 1 = 0`, and zero ready replicas cleared it immediately. `--wait` returned
before a single pod had been probed, so `--atomic` had nothing to react to.

Production was never exposed to this — it already ran `maxUnavailable: 0` for
unrelated reasons — but dev and test, the two environments whose entire job is
to catch a bad release before production sees it, could not fail a deploy.

## Decision

`maxUnavailable: 0` in the chart defaults, so the arithmetic can never collapse
the bar to zero again.

For dev and test, which run a single replica, `strategy.type: Recreate`. Helm
computes `MaxUnavailable` as zero for any non-RollingUpdate strategy, so the bar
is the full replica count and the deploy blocks until the new pod is genuinely
Ready.

## Options considered

**`maxUnavailable: 0` with `RollingUpdate` everywhere.** Correct semantics, and
it was tried first. It fails for a different reason: surge-before-drain means
seven services each run two Java pods during a rollout, which does not fit in a
16GB laptop's Docker VM or a free GitHub Actions runner. The measured result was
a rollout that timed out on memory pressure — a deploy failure that says nothing
about the release.

**Raising the memory ceiling.** Rejected. The constraint is the machine, and
making development require a bigger one is a worse trade than a few seconds of
downtime in an environment that nobody depends on.

**`Recreate` in dev and test, `RollingUpdate` with `maxUnavailable: 0` in
production.** Chosen. Production has two replicas, so surge costs one extra pod
and buys a zero-downtime rollout. Dev and test have one replica, so there is no
rollout to speak of and nothing to protect.

**Checking readiness in the pipeline instead of relying on Helm.** Rejected as
the primary mechanism — a second implementation of a check Helm already makes is
a second thing to get wrong. The smoke test that runs after every deploy remains
as the independent check, because `Ready` and `working` are different claims.

## Consequences

**Good.** A deploy that does not come up now fails. Re-running the same broken
release after the change: Helm waited the full three minutes, exited 1 with
`UPGRADE FAILED ... has been rolled back due to atomic being set`, recorded the
revision as `failed`, and restored the previous one. All eleven pods returned to
`1/1` and the smoke test passed against the restored release.

**Bad.** Dev and test take a few seconds of downtime per deploy, and a Recreate
rollout cannot be halted half-way — the old pod is already gone. Acceptable in
environments with one replica and no users.

**Worth remembering.** `--wait` is not a promise on its own; it is a comparison
against a number the chart chooses. Any future change to replica counts or
rollout strategy should be followed by breaking a release on purpose and
checking that the pipeline still notices.
