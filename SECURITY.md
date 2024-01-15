# Security

How secrets are handled, what is scanned, and how to report a problem.

> This is a portfolio project. Every credential in it is fictitious, and the
> demo passwords published in the README exist only under the `local`, `dev` and
> `test` profiles.

---

## Secrets

**Nothing secret is committed.** Three mechanisms enforce that rather than
relying on it being remembered:

| Where | How | Enforced by |
| --- | --- | --- |
| Local development | `.env.example` with placeholders; the real `.env` is git-ignored | `.gitignore` |
| CI/CD | GitHub Secrets, injected as environment variables | GitHub |
| Kubernetes | Secrets created at deploy time from GitHub Secrets, never templated into a chart | `deploy/helm` |
| Every commit | gitleaks scans the full history on every pull request | `ci.yml` |

### The JWT signing key

The one secret every service needs. It is:

- Read from `JWT_SECRET`, with **no default**. `JwtProperties` throws on startup
  if it is missing or shorter than 32 bytes. A default signing key is exactly the
  kind of thing that reaches production unnoticed, so there is not one.
- Created as a Kubernetes Secret at deploy time:
  ```bash
  kubectl create secret generic dinehub-jwt \
    --namespace dinehub-prod \
    --from-literal=secret="${JWT_SECRET}" \
    --dry-run=client -o yaml | kubectl apply -f -
  ```
- Rotatable. Rotating it invalidates every issued token, so users must log in
  again — see [docs/RELEASE_RUNBOOK.md](docs/RELEASE_RUNBOOK.md) for the
  procedure that does it without a visible outage.

### Database credentials

One credential per service per environment. A service can reach only its own
database — enforced by the credential and, independently, by a NetworkPolicy.

---

## Authentication and authorisation

- **Passwords** are hashed with BCrypt at cost 12. Cost 10 is the library default
  and is fast enough on modern hardware to be worth increasing; 12 costs about
  250 ms, which is unnoticeable on a login and meaningfully expensive to an
  attacker holding the hashes.
- **Tokens** are short-lived (15 minutes) with a separate refresh token (7 days).
  A refresh token presented as an access token is rejected — otherwise every
  session would silently extend to the refresh lifetime.
- **The gateway validates** every token at the edge and strips any identity
  headers the client supplied. Without that strip, impersonating an admin is one
  `curl -H "X-User-Role: ADMIN"` away.
- **Services validate too.** That duplication is deliberate: a request that
  reaches a service by any route other than the gateway is still authenticated.
- **Self-registration always creates a CUSTOMER**, whatever the request body
  says. Honouring a client-supplied `role` field would be privilege escalation by
  JSON.
- **Login does not reveal whether an account exists.** The same message and
  roughly the same work either way, or the endpoint becomes an enumeration oracle.

---

## Containers

- Every image runs as UID 10001, never root. The Dockerfile creates the account
  and the Kubernetes `securityContext` asserts the same UID, so the two cannot
  disagree.
- `readOnlyRootFilesystem: true` with an explicit `emptyDir` for `/tmp`.
- `allowPrivilegeEscalation: false`, all capabilities dropped.
- Multi-stage builds: the runtime image has a JRE and the application layers,
  with no compiler, no build tools and no package manager cache.
- Base images are pinned to a digest in the deployed manifests.

---

## Scanning

| Scan | Tool | When | Fails the build on |
| --- | --- | --- | --- |
| Secrets in git history | gitleaks | Every PR | Any finding |
| Container images | Trivy | Every PR, nightly | CRITICAL with a fix available |
| Helm charts and Dockerfiles | Trivy config | Every PR | HIGH and above |
| Java and TypeScript code | CodeQL | Every PR, nightly | Any alert |
| Dependencies | Dependency Review + Dependabot | Every PR, weekly | High-severity advisories |

The Trivy gate is "CRITICAL **with a fix available**" on purpose. Failing on
unfixable CRITICALs means the pipeline is red for reasons nobody can act on,
which trains people to merge past it — and then it catches nothing.

Nightly scans run against the deployed images, not just the PR, because a
dependency that was clean at merge time may not be clean next week.

---

## Network

- NetworkPolicies default-deny in every namespace. The gateway is the only
  workload reachable from the ingress; each service reaches only its own database
  and RabbitMQ.
- TLS terminates at the ingress.
- No service exposes a NodePort or a LoadBalancer.

---

## Reporting a vulnerability

This is a portfolio repository with no production deployment. If you find
something anyway, open a GitHub issue — or, if it is sensitive, use GitHub's
private vulnerability reporting on the repository.
