# ADR 0002 — Build once, deploy the same image everywhere

**Status:** Accepted
**Date:** 2024-01-19

## Context

Each environment needs the application deployed to it. The pipeline could build
per environment, or build once and promote the artefact.

## Decision

Images are built exactly once per commit, pushed to GHCR tagged with
`<version>-<short-sha>` and the full SHA, and the **same digest** is deployed to
dev, test and prod. Environment differences come only from Helm values and
environment variables.

## Options considered

**Build per environment.** Simpler pipeline, no registry needed for promotion.
Rejected: the thing tested in test is then not the thing shipped to prod. It is
a different build of the same source, which is close enough to be reassuring and
different enough to fail — a dependency resolved to a newer patch version, a base
image that moved, a build cache that differed. The whole point of testing in test
is to learn something about prod, and this breaks that chain.

**Build once, retag per environment.** Common, and almost right. Rejected
because a tag is mutable: `v1.2.3-dev` and `v1.2.3-prod` can point at different
digests and nothing says so. Deploying by digest removes the question.

**Build once, deploy by digest.** Chosen. The digest is in the job summary and in
the release notes, so "is prod running what test approved?" is answered by
reading two lines, not by trusting a process.

## Consequences

**Good.** What was tested is what ships. Promotion is a Helm value change and
takes seconds, not a rebuild. A rollback is redeploying a previous digest, which
is guaranteed to be the artefact that previously worked.

**Bad.** The image must contain every environment's capability and select at
runtime, so nothing environment-specific can be baked in at build time. In
practice this is a constraint worth having: it forces configuration to be
genuinely external, which is what makes the image portable in the first place.

**Consequence worth stating.** Because the image is identical, Swagger UI and the
seed migrations are *present* in the production image and disabled by
configuration. Both are gated on the Spring profile — `prod` excludes the seed
migration location entirely, so the seed SQL cannot run even if the profile were
set wrongly. Relying on configuration alone to keep demo credentials out of a
production database would not be good enough.
