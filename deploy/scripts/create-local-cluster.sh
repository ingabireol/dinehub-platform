#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# create-local-cluster.sh — a k3d cluster with dev, test and prod namespaces.
#
#   ./deploy/scripts/create-local-cluster.sh
#   ./deploy/scripts/create-local-cluster.sh --delete
#
# This is Mode B from docs/PIPELINE.md: real, persistent environments that a
# self-hosted runner can deploy to repeatedly. Mode A (ephemeral kind clusters
# inside the GitHub runner) needs none of this.
#
# k3d rather than kind: it bundles a load balancer and an ingress controller, so
# `http://dev.dinehub.local` works without extra plumbing.
# ---------------------------------------------------------------------------

set -o errexit
set -o nounset
set -o pipefail

CLUSTER_NAME="${CLUSTER_NAME:-dinehub}"
HTTP_PORT="${HTTP_PORT:-8090}"
HTTPS_PORT="${HTTPS_PORT:-8443}"
AGENTS="${AGENTS:-2}"
ENVIRONMENTS=(dev test prod)
DELETE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --delete) DELETE=true; shift ;;
    --name)   CLUSTER_NAME="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^# ---/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: $1 is not installed." >&2
    echo "       $2" >&2
    exit 127
  }
}

require k3d   "brew install k3d  (or see https://k3d.io)"
require kubectl "brew install kubectl"
require helm  "brew install helm"

if [[ "$DELETE" == "true" ]]; then
  echo "Deleting cluster ${CLUSTER_NAME}"
  k3d cluster delete "$CLUSTER_NAME"
  exit 0
fi

if k3d cluster list 2>/dev/null | grep -q "^${CLUSTER_NAME}\s"; then
  echo "Cluster ${CLUSTER_NAME} already exists. Delete it first with --delete."
  exit 0
fi

echo "Creating cluster ${CLUSTER_NAME} (${AGENTS} agents)"
k3d cluster create "$CLUSTER_NAME" \
  --agents "$AGENTS" \
  --port "${HTTP_PORT}:80@loadbalancer" \
  --port "${HTTPS_PORT}:443@loadbalancer" \
  --k3s-arg "--disable=metrics-server@server:0" \
  --wait

kubectl cluster-info

# --- Namespaces -------------------------------------------------------------
for env in "${ENVIRONMENTS[@]}"; do
  ns="dinehub-${env}"
  echo "Creating namespace ${ns}"
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
  kubectl label namespace "$ns" "dinehub.io/environment=${env}" --overwrite

  # Quotas on every namespace, including dev. A runaway dev workload can
  # otherwise exhaust node resources that production needs — a namespace is not
  # a resource boundary unless you make it one.
  kubectl apply -f - <<EOF
apiVersion: v1
kind: ResourceQuota
metadata:
  name: dinehub-quota
  namespace: ${ns}
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi
    pods: "40"
    persistentvolumeclaims: "10"
---
apiVersion: v1
kind: LimitRange
metadata:
  name: dinehub-limits
  namespace: ${ns}
spec:
  limits:
    # A pod with no limits can consume a whole node. This makes forgetting to
    # set them a non-event rather than an incident.
    - type: Container
      default:
        cpu: 500m
        memory: 512Mi
      defaultRequest:
        cpu: 100m
        memory: 256Mi
      max:
        cpu: "2"
        memory: 2Gi
EOF
done

# --- Secrets ----------------------------------------------------------------
# Created here with development values. The pipeline creates the real ones from
# GitHub Secrets at deploy time; nothing secret is ever templated into a chart.
echo ""
echo "Creating development secrets"
for env in "${ENVIRONMENTS[@]}"; do
  ns="dinehub-${env}"
  kubectl create secret generic dinehub-jwt \
    --namespace "$ns" \
    --from-literal=secret="local-${env}-signing-key-not-for-any-real-use-32b" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl create secret generic dinehub-db \
    --namespace "$ns" \
    --from-literal=username=dinehub \
    --from-literal=password=dinehub \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl create secret generic dinehub-rabbitmq \
    --namespace "$ns" \
    --from-literal=username=dinehub \
    --from-literal=password=dinehub \
    --dry-run=client -o yaml | kubectl apply -f -
done

# --- Ingress hostnames ------------------------------------------------------
cat <<EOF

────────────────────────────────────────────────────────────────────
Cluster ${CLUSTER_NAME} is ready.

  Namespaces : dinehub-dev, dinehub-test, dinehub-prod
  Ingress    : http://localhost:${HTTP_PORT}

Add these to /etc/hosts so the ingress hostnames resolve:

  127.0.0.1  dev.dinehub.local test.dinehub.local dinehub.local

Then deploy:

  make deploy ENV=dev
  make smoke  ENV=dev

For Mode B (a self-hosted runner deploying here), export a kubeconfig:

  kubectl config view --raw --minify --flatten > kubeconfig-dev.yaml

and add its contents as the KUBECONFIG_DEV repository secret.
────────────────────────────────────────────────────────────────────
EOF
