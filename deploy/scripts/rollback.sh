#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# rollback.sh — roll an environment back to its previous release.
#
#   ./deploy/scripts/rollback.sh --env prod
#   ./deploy/scripts/rollback.sh --env prod --revision 7
#   ./deploy/scripts/rollback.sh --env prod --list
#
# The pipeline calls this automatically when smoke tests fail after a deploy.
# A human calls it when something was missed and production needs to go back.
#
# It deliberately does NOT revert database migrations — see
# docs/ROLLBACK_RUNBOOK.md for why, and what to do when a migration is involved.
# ---------------------------------------------------------------------------

set -o errexit
set -o nounset
set -o pipefail

ENVIRONMENT=""
REVISION=""
RELEASE="${RELEASE:-dinehub}"
LIST=false
FORCE=false
TIMEOUT="${TIMEOUT:-10m}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)      ENVIRONMENT="$2"; shift 2 ;;
    --revision) REVISION="$2"; shift 2 ;;
    --list)     LIST=true; shift ;;
    --force)    FORCE=true; shift ;;
    -h|--help)  sed -n '2,/^# ---/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$ENVIRONMENT" ]] || { echo "--env dev|test|prod is required" >&2; exit 2; }
NAMESPACE="dinehub-${ENVIRONMENT}"

command -v helm >/dev/null 2>&1 || { echo "helm is not installed" >&2; exit 127; }

if ! helm status "$RELEASE" --namespace "$NAMESPACE" >/dev/null 2>&1; then
  echo "No release '${RELEASE}' in ${NAMESPACE}." >&2
  exit 1
fi

echo ""
echo "Release history for ${RELEASE} in ${NAMESPACE}:"
helm history "$RELEASE" --namespace "$NAMESPACE" --max 10

if [[ "$LIST" == "true" ]]; then
  exit 0
fi

CURRENT="$(helm history "$RELEASE" --namespace "$NAMESPACE" -o json \
           | grep -o '"revision":[0-9]*' | tail -1 | cut -d: -f2)"

if [[ -z "$REVISION" ]]; then
  # Helm treats revision 0 as "the previous one", which is what an automatic
  # rollback wants.
  REVISION=0
  TARGET_DESC="the previous revision"
else
  TARGET_DESC="revision ${REVISION}"
fi

echo ""
echo "About to roll ${NAMESPACE} back from revision ${CURRENT} to ${TARGET_DESC}."
echo ""
echo "This does NOT revert database migrations. If the release being rolled back"
echo "applied a destructive migration, read docs/ROLLBACK_RUNBOOK.md first —"
echo "old code against a new schema fails in ways that are worse than the"
echo "problem you are rolling back."
echo ""

# Production gets a confirmation prompt unless the caller is the pipeline.
if [[ "$ENVIRONMENT" == "prod" && "$FORCE" == "false" && -t 0 ]]; then
  read -r -p "Type 'rollback prod' to continue: " confirm
  [[ "$confirm" == "rollback prod" ]] || { echo "Aborted."; exit 1; }
fi

echo "Rolling back…"
helm rollback "$RELEASE" "$REVISION" \
  --namespace "$NAMESPACE" \
  --wait --timeout "$TIMEOUT" \
  --cleanup-on-fail

echo ""
echo "Rolled back. Current state:"
helm status "$RELEASE" --namespace "$NAMESPACE" | head -20
kubectl get pods --namespace "$NAMESPACE"

echo ""
echo "Now verify the environment actually works, not just that the pods restarted:"
echo "  ./deploy/scripts/smoke-test.sh --env ${ENVIRONMENT}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### Rollback — `%s`\n\n' "$NAMESPACE"
    printf 'Rolled back from revision %s to %s.\n\n' "$CURRENT" "$TARGET_DESC"
    printf '> Database migrations were **not** reverted.\n'
  } >> "$GITHUB_STEP_SUMMARY"
fi
