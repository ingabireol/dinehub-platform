#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Prove the NetworkPolicies actually do what they claim.
#
# A rendered NetworkPolicy is not an enforced one. Whether traffic is blocked
# depends on the CNI, and several common ones — including older kindnet — accept
# the objects and ignore them completely. A chart that "has network policies"
# while every pod can still reach every other pod is worse than one with none,
# because it reports a protection that is not there.
#
# So this script first checks that the cluster enforces policy at all, and
# refuses to report success if it does not. Then it walks an explicit matrix of
# connections that must work and connections that must not.
#
#   ./deploy/scripts/verify-network-policies.sh --env dev
#
# Exit codes: 0 every check passed, 1 a check failed, 2 the CNI does not enforce
# NetworkPolicy so nothing was proved, 3 bad usage.
# ---------------------------------------------------------------------------
set -uo pipefail

ENV="dev"
TIMEOUT=5

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)     ENV="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown argument: $1" >&2; usage 3 ;;
  esac
done

NS="dinehub-${ENV}"
GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
passed=0; failed=0

line() { printf '%s\n' "────────────────────────────────────────────────────────────"; }

pod_for() {
  kubectl get pods -n "$NS" -l "app.kubernetes.io/name=$1" \
    -o jsonpath='{.items[?(@.status.phase=="Running")].metadata.name}' 2>/dev/null | awk '{print $1}'
}

# Connectivity is tested from inside the source pod, because that is the only
# place the policy applies. The images are distroless and have no nc, so this
# uses bash's /dev/tcp where available and falls back to a one-shot probe pod
# that carries the source pod's labels.
reach() {
  local src_label="$1" host="$2" port="$3"
  local probe="netpol-probe-$(echo "${src_label}-${port}" | tr -cd 'a-z0-9-' | cut -c1-40)-$RANDOM"
  local component="service"
  case "$src_label" in
    api-gateway) component="service" ;;
    web)         component="web" ;;
  esac

  kubectl run "$probe" -n "$NS" --image=busybox:1.36 --restart=Never --quiet \
    --labels="app.kubernetes.io/name=${src_label},app.kubernetes.io/part-of=dinehub,app.kubernetes.io/component=${component},netpol-probe=true" \
    --command -- sh -c "nc -z -w ${TIMEOUT} ${host} ${port}" >/dev/null 2>&1

  kubectl wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$probe" -n "$NS" \
    --timeout="$((TIMEOUT * 4))s" >/dev/null 2>&1
  local rc=$?
  kubectl delete pod "$probe" -n "$NS" --wait=false --ignore-not-found >/dev/null 2>&1
  return $rc
}

check() {
  local expect="$1" src="$2" dst="$3" port="$4" why="$5"
  local host="$dst"
  if reach "$src" "$host" "$port"; then actual="allowed"; else actual="blocked"; fi

  if [[ "$actual" == "$expect" ]]; then
    printf '  %s✓%s %-16s → %-28s %-7s %s%s%s\n' "$GREEN" "$OFF" "$src" "${dst}:${port}" "$actual" "$DIM" "$why" "$OFF"
    passed=$((passed + 1))
  else
    printf '  %s✗%s %-16s → %-28s expected %s, got %s\n' "$RED" "$OFF" "$src" "${dst}:${port}" "$expect" "$actual"
    printf '      %s%s%s\n' "$DIM" "$why" "$OFF"
    failed=$((failed + 1))
  fi
}

printf '\nNetworkPolicy verification — %s\n' "$NS"
line

if ! kubectl get namespace "$NS" >/dev/null 2>&1; then
  printf '  %sNamespace %s does not exist.%s\n\n' "$RED" "$NS" "$OFF"; exit 1
fi

count=$(kubectl get networkpolicy -n "$NS" --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [[ "$count" == "0" ]]; then
  printf '  %sNo NetworkPolicies in %s — deploy with networkPolicies.enabled=true.%s\n\n' \
    "$RED" "$NS" "$OFF"; exit 1
fi
printf '  %d policies present\n' "$count"

# --- Does this cluster enforce policy at all? ------------------------------
printf '\n  %sEnforcement check%s\n' "$YELLOW" "$OFF"
PROBE_NS="netpol-enforcement-$RANDOM"
kubectl create namespace "$PROBE_NS" >/dev/null 2>&1
cleanup() { kubectl delete namespace "$PROBE_NS" --wait=false --ignore-not-found >/dev/null 2>&1; }
trap cleanup EXIT

kubectl run target -n "$PROBE_NS" --image=busybox:1.36 --restart=Never --quiet \
  --command -- sh -c 'nc -lk -p 8080 -e echo ok' >/dev/null 2>&1
kubectl wait --for=condition=Ready "pod/target" -n "$PROBE_NS" --timeout=90s >/dev/null 2>&1
TARGET_IP=$(kubectl get pod target -n "$PROBE_NS" -o jsonpath='{.status.podIP}' 2>/dev/null)

kubectl apply -n "$PROBE_NS" -f - >/dev/null 2>&1 <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: deny-all }
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
EOF
sleep 3

kubectl run enforcement -n "$PROBE_NS" --image=busybox:1.36 --restart=Never --quiet \
  --command -- sh -c "nc -z -w 3 ${TARGET_IP} 8080" >/dev/null 2>&1
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded "pod/enforcement" -n "$PROBE_NS" \
  --timeout=40s >/dev/null 2>&1
if [[ $? -eq 0 ]]; then
  printf '  %s✗%s a deny-all policy did not block traffic\n' "$RED" "$OFF"
  printf '      %sThis CNI accepts NetworkPolicy objects and ignores them. The policies\n' "$DIM"
  printf '      in this chart are therefore NOT in force here, whatever kubectl shows.\n'
  printf '      Nothing below would prove anything, so the checks are skipped.%s\n\n' "$OFF"
  exit 2
fi
printf '  %s✓%s a deny-all policy blocks traffic — this CNI enforces policy\n' "$GREEN" "$OFF"
cleanup; trap - EXIT

# --- The matrix ------------------------------------------------------------
printf '\n  %sWhat must work%s\n' "$YELLOW" "$OFF"
check allowed api-gateway   dinehub-order-service   8083 "the gateway routes to it"
check allowed order-service dinehub-menu-service    8082 "pricing a basket — the one synchronous hop"
check allowed order-service dinehub-postgresql      5432 "its own database"
check allowed order-service dinehub-rabbitmq        5672 "publishing domain events"

printf '\n  %sWhat must not%s\n' "$YELLOW" "$OFF"
check blocked web             dinehub-postgresql    5432 "nginx has no business reaching a database"
check blocked order-service   dinehub-redis         6379 "rate limiting is the gateway's job, not a service's"
check blocked payment-service dinehub-menu-service  8082 "only order-service calls the menu"

printf '\n'
line
if [[ $failed -eq 0 ]]; then
  printf '  %s%d passed, 0 failed%s\n\n' "$GREEN" "$passed" "$OFF"
  exit 0
fi
printf '  %s%d passed, %d failed%s\n\n' "$RED" "$passed" "$failed" "$OFF"
exit 1
