#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# smoke-test.sh — end-to-end verification of a deployed environment.
#
#   ./deploy/scripts/smoke-test.sh --env dev
#   ./deploy/scripts/smoke-test.sh --url http://localhost:8080
#
# This is the gate the delivery pipeline uses to decide whether a deployment
# worked. It therefore has to test what a user actually does, not whether the
# pods are running — a deployment where every pod is Ready and no order can be
# placed has failed, and only an end-to-end check notices.
#
# Exit codes: 0 all passed, 1 a check failed, 2 bad usage.
# ---------------------------------------------------------------------------

set -o errexit
set -o nounset
set -o pipefail

ENVIRONMENT=""
BASE_URL=""
TIMEOUT=10
VERBOSE=false
PASSED=0
FAILED=0

# A password that satisfies the registration policy: 12+ characters, mixed case,
# a digit.
readonly TEST_PASSWORD="SmokeTest1Pass"

usage() {
  sed -n '2,/^# ---/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)     ENVIRONMENT="$2"; shift 2 ;;
    --url)     BASE_URL="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --verbose) VERBOSE=true; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown argument: $1" >&2; usage 2 ;;
  esac
done

if [[ -z "$BASE_URL" ]]; then
  case "$ENVIRONMENT" in
    dev)   BASE_URL="http://dev.dinehub.local" ;;
    test)  BASE_URL="http://test.dinehub.local" ;;
    prod)  BASE_URL="http://dinehub.local" ;;
    local) BASE_URL="http://localhost:8080" ;;
    *)     echo "Pass --env dev|test|prod|local, or --url" >&2; usage 2 ;;
  esac
fi

# --- Output ----------------------------------------------------------------
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; printf '      %s\n' "${2:-}"; FAILED=$((FAILED + 1)); }
info() { [[ "$VERBOSE" == "true" ]] && printf '      %s\n' "$1" || true; }

api() {
  local method="$1" path="$2" expected="$3"; shift 3
  local response status
  response="$(curl -sS -X "$method" "${BASE_URL}${path}" \
                --max-time "$TIMEOUT" -w '\n%{http_code}' "$@" 2>&1 || echo $'\n000')"
  status="$(tail -n1 <<< "$response")"
  BODY="$(sed '$d' <<< "$response")"
  [[ "$status" == "$expected" ]]
}

printf '\nSmoke test — %s\n' "$BASE_URL"
printf '%s\n' "────────────────────────────────────────────────────────────"

# --- 1. The platform answers at all ----------------------------------------
printf '\n  Availability\n'

if api GET /actuator/health/readiness 200; then
  pass "gateway reports ready"
else
  fail "gateway is not ready" "$BODY"
  # Nothing below can pass if the front door is shut, so stop here with a clear
  # message rather than producing twelve confusing failures.
  printf '\n%s\n' "────────────────────────────────────────────────────────────"
  printf '  \033[31mThe gateway is not reachable. Aborting.\033[0m\n\n'
  exit 1
fi

# --- 2. The menu is readable without a token --------------------------------
printf '\n  Public access\n'

if api GET /api/v1/menu/items 200; then
  pass "menu is readable anonymously"
  info "$(head -c 120 <<< "$BODY")"
else
  fail "menu is not readable" "$BODY"
fi

if api GET /api/v1/menu/categories 200; then
  pass "categories are readable anonymously"
else
  fail "categories are not readable" "$BODY"
fi

# --- 3. Registration and login ----------------------------------------------
printf '\n  Authentication\n'

EMAIL="smoke-$(date +%s)-$RANDOM@dinehub.local"

if api POST /api/v1/auth/register 201 \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"${EMAIL}\",\"password\":\"${TEST_PASSWORD}\",\"fullName\":\"Smoke Test\"}"; then
  pass "a new customer can register"
  TOKEN="$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' <<< "$BODY")"
  REFRESH="$(sed -n 's/.*"refreshToken":"\([^"]*\)".*/\1/p' <<< "$BODY")"
else
  fail "registration failed" "$BODY"
  TOKEN=""
fi

if [[ -n "${TOKEN:-}" ]]; then
  if api GET /api/v1/auth/me 200 -H "Authorization: Bearer ${TOKEN}"; then
    pass "the issued token authenticates"
  else
    fail "the issued token does not authenticate" "$BODY"
  fi

  if api POST /api/v1/auth/refresh 200 \
       -H 'Content-Type: application/json' \
       -d "{\"refreshToken\":\"${REFRESH}\"}"; then
    pass "the refresh token works"
  else
    fail "refresh failed" "$BODY"
  fi
fi

if api POST /api/v1/auth/login 403 \
     -H 'Content-Type: application/json' \
     -d "{\"email\":\"${EMAIL}\",\"password\":\"DefinitelyWrong1\"}"; then
  pass "a wrong password is refused"
else
  fail "a wrong password was not refused with 403" "$BODY"
fi

# --- 4. Authorisation --------------------------------------------------------
printf '\n  Authorisation\n'

if api GET /api/v1/orders/mine 401; then
  pass "an unauthenticated request is refused"
else
  fail "an unauthenticated request was not refused" "$BODY"
fi

# The control that stops impersonation by header. If this ever passes with 2xx,
# anyone can become an admin with one curl flag.
if api POST /api/v1/menu/items 401 \
     -H 'X-User-Role: ADMIN' \
     -H 'X-User-Id: 00000000-0000-0000-0000-000000000000' \
     -H 'Content-Type: application/json' \
     -d '{"categoryId":"aaaaaaaa-0000-0000-0000-000000000002","name":"Spoofed","price":1.00,"preparationMinutes":5}'; then
  pass "spoofed identity headers are rejected"
else
  fail "SPOOFED IDENTITY HEADERS WERE ACCEPTED" "$BODY"
fi

if [[ -n "${TOKEN:-}" ]]; then
  # A CUSTOMER token must not be able to write to the menu.
  if api POST /api/v1/menu/items 403 \
       -H "Authorization: Bearer ${TOKEN}" \
       -H 'Content-Type: application/json' \
       -d '{"categoryId":"aaaaaaaa-0000-0000-0000-000000000002","name":"Nope","price":1.00,"preparationMinutes":5}'; then
    pass "a customer cannot modify the menu"
  else
    fail "a customer was able to reach the menu write endpoint" "$BODY"
  fi
fi

# --- 5. The order flow -------------------------------------------------------
printf '\n  Order flow\n'

if [[ -n "${TOKEN:-}" ]]; then
  ITEM_ID="$(curl -sS --max-time "$TIMEOUT" "${BASE_URL}/api/v1/menu/items?availableOnly=true" \
             | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)"

  if [[ -z "$ITEM_ID" ]]; then
    fail "no available menu item to order" "the menu may not be seeded in this environment"
  elif api POST /api/v1/orders 201 \
         -H "Authorization: Bearer ${TOKEN}" \
         -H 'Content-Type: application/json' \
         -d "{\"items\":[{\"menuItemId\":\"${ITEM_ID}\",\"quantity\":1}],\"deliveryAddress\":\"Smoke Test Lane\"}"; then
    pass "an order can be placed"
    ORDER_ID="$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' <<< "$BODY" | head -1)"
    info "order ${ORDER_ID}"

    if api GET "/api/v1/orders/${ORDER_ID}" 200 -H "Authorization: Bearer ${TOKEN}"; then
      pass "the order is readable by its owner"
    else
      fail "the order is not readable by its owner" "$BODY"
    fi

    # Payment is asynchronous. Poll rather than sleep: a fixed sleep is either
    # flaky on a slow environment or wasted time on a fast one.
    printf '  … waiting for the payment event to be processed\n'
    MOVED=false
    for _ in $(seq 1 20); do
      if api GET "/api/v1/orders/${ORDER_ID}" 200 -H "Authorization: Bearer ${TOKEN}"; then
        STATUS="$(sed -n 's/.*"status":"\([^"]*\)".*/\1/p' <<< "$BODY" | head -1)"
        if [[ "$STATUS" != "PLACED" ]]; then
          MOVED=true
          break
        fi
      fi
      sleep 1
    done

    if [[ "$MOVED" == "true" ]]; then
      # CANCELLED is a pass too: it means payment ran and declined, which is the
      # simulated failure path working, not a broken pipeline.
      pass "the order moved out of PLACED (now ${STATUS}) — the event chain works"
    else
      fail "the order is still PLACED after 20s" \
           "payment-service may not be consuming order.placed; check the DLQ"
    fi

    if api GET /api/v1/notifications 200 -H "Authorization: Bearer ${TOKEN}"; then
      pass "notifications are readable"
    else
      fail "notifications are not readable" "$BODY"
    fi
  else
    fail "order placement failed" "$BODY"
  fi
fi

# --- 6. Rejections that must still work --------------------------------------
printf '\n  Input validation\n'

if [[ -n "${TOKEN:-}" ]]; then
  if api POST /api/v1/orders 400 \
       -H "Authorization: Bearer ${TOKEN}" \
       -H 'Content-Type: application/json' \
       -d '{"items":[]}'; then
    pass "an empty order is rejected"
  else
    fail "an empty order was not rejected" "$BODY"
  fi

  if api POST /api/v1/orders 400 \
       -H "Authorization: Bearer ${TOKEN}" \
       -H 'Content-Type: application/json' \
       -d '{"items":[{"menuItemId":"99999999-9999-9999-9999-999999999999","quantity":1}]}'; then
    pass "an order for an unknown item is rejected"
  else
    fail "an order for an unknown item was not rejected" "$BODY"
  fi
fi

# --- Summary -----------------------------------------------------------------
printf '\n%s\n' "────────────────────────────────────────────────────────────"
printf '  %d passed, %d failed\n\n' "$PASSED" "$FAILED"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### Smoke test — `%s`\n\n' "$BASE_URL"
    printf '| Result | Count |\n|---|---|\n'
    printf '| Passed | %d |\n| Failed | %d |\n\n' "$PASSED" "$FAILED"
  } >> "$GITHUB_STEP_SUMMARY"
fi

[[ "$FAILED" -eq 0 ]]
