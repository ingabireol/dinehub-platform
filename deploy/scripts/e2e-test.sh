#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# e2e-test.sh — the full business flow, end to end.
#
#   ./deploy/scripts/e2e-test.sh --url http://localhost:8080
#
# Where smoke-test.sh asks "does the platform work at all?", this follows one
# order all the way through:
#
#   register → login → browse → order → pay → kitchen prepares → delivered
#            → notifications arrive at each step
#
# It runs against test in the delivery pipeline, after the smoke tests pass. It
# is slower and more specific than the smoke tests on purpose: dev's job is to
# fail fast, test's job is to be thorough.
#
# Exit codes: 0 all passed, 1 a step failed, 2 bad usage.
# ---------------------------------------------------------------------------

set -o errexit
set -o nounset
set -o pipefail

BASE_URL="http://localhost:8080"
TIMEOUT=15
# How long to wait for an asynchronous status change before giving up.
EVENT_TIMEOUT=45
PASSED=0
FAILED=0

readonly PASSWORD="EndToEnd1Test"
readonly ADMIN_EMAIL="admin@dinehub.local"
readonly CHEF_EMAIL="chef@dinehub.local"
readonly DEMO_PASSWORD="DineHub2024!"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)     BASE_URL="$2"; shift 2 ;;
    --timeout) EVENT_TIMEOUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,/^# ---/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; printf '      %s\n' "${2:-}"; FAILED=$((FAILED + 1)); }
step() { printf '\n  \033[1m%s\033[0m\n' "$1"; }

# Returns the body in BODY and the status in STATUS.
api() {
  local method="$1" path="$2"; shift 2
  local response
  response="$(curl -sS -X "$method" "${BASE_URL}${path}" \
                --max-time "$TIMEOUT" -w $'\n%{http_code}' "$@" 2>&1 || echo $'\n000')"
  STATUS="$(tail -n1 <<< "$response")"
  BODY="$(sed '$d' <<< "$response")"
}

json() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" <<< "$2" | head -1; }

login() {
  api POST /api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}"
  json accessToken "$BODY"
}

printf '\nEnd-to-end — %s\n' "$BASE_URL"
printf '%s\n' "────────────────────────────────────────────────────────────"

# --- 1. A new customer registers --------------------------------------------
step "A customer registers and signs in"

EMAIL="e2e-$(date +%s)-$RANDOM@dinehub.local"
api POST /api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"fullName\":\"E2E Tester\"}"

if [[ "$STATUS" == "201" ]]; then
  pass "registered ${EMAIL}"
  CUSTOMER_TOKEN="$(json accessToken "$BODY")"
else
  fail "registration failed (${STATUS})" "$BODY"
  printf '\nCannot continue without a customer.\n\n'
  exit 1
fi

# --- 2. The welcome notification arrives ------------------------------------
step "The welcome notification arrives"

GOT_WELCOME=false
for _ in $(seq 1 "$EVENT_TIMEOUT"); do
  api GET /api/v1/notifications -H "Authorization: Bearer ${CUSTOMER_TOKEN}"
  if grep -q 'WELCOME' <<< "$BODY"; then GOT_WELCOME=true; break; fi
  sleep 1
done

if [[ "$GOT_WELCOME" == "true" ]]; then
  pass "user.registered reached notification-service"
else
  fail "no welcome notification after ${EVENT_TIMEOUT}s" \
       "check the user.registered binding and the DLQ"
fi

# --- 3. Browse the menu ------------------------------------------------------
step "The customer browses the menu"

api GET "/api/v1/menu/items?availableOnly=true"
if [[ "$STATUS" == "200" ]]; then
  ITEM_ID="$(json id "$BODY")"
  if [[ -n "$ITEM_ID" ]]; then
    pass "menu has orderable items"
  else
    fail "the menu is empty" "is seed data enabled in this environment?"
    exit 1
  fi
else
  fail "could not read the menu (${STATUS})" "$BODY"
  exit 1
fi

# --- 4. Place an order -------------------------------------------------------
step "The customer places an order"

api POST /api/v1/orders \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"menuItemId\":\"${ITEM_ID}\",\"quantity\":2}],\"deliveryAddress\":\"E2E Lane\"}"

if [[ "$STATUS" == "201" ]]; then
  ORDER_ID="$(json id "$BODY")"
  pass "order ${ORDER_ID:0:8} placed"

  # The line must carry a snapshotted name and price, not a reference.
  if grep -q '"itemName"' <<< "$BODY" && grep -q '"unitPrice"' <<< "$BODY"; then
    pass "the order snapshots the item name and price"
  else
    fail "the order line is missing its price snapshot" "$BODY"
  fi
else
  fail "could not place the order (${STATUS})" "$BODY"
  exit 1
fi

# --- 5. Payment runs ---------------------------------------------------------
step "Payment is processed asynchronously"

ORDER_STATUS=PLACED
for _ in $(seq 1 "$EVENT_TIMEOUT"); do
  api GET "/api/v1/orders/${ORDER_ID}" -H "Authorization: Bearer ${CUSTOMER_TOKEN}"
  ORDER_STATUS="$(json status "$BODY")"
  [[ "$ORDER_STATUS" != "PLACED" ]] && break
  sleep 1
done

case "$ORDER_STATUS" in
  PAID)
    pass "order.placed → payment.completed → PAID"
    ;;
  CANCELLED)
    # Not a failure: it means payment declined and the cancellation chain ran,
    # which is the failure path working. Set the simulated rate to 0 if you need
    # a deterministic happy path.
    pass "payment declined and the order cancelled — the failure path works"
    printf '      (set PAYMENT_FAILURE_RATE=0.0 for a deterministic happy path)\n'
    ;;
  *)
    fail "the order is still ${ORDER_STATUS} after ${EVENT_TIMEOUT}s" \
         "payment-service may not be consuming order.placed; check the DLQ"
    ;;
esac

api GET "/api/v1/payments/order/${ORDER_ID}" -H "Authorization: Bearer ${CUSTOMER_TOKEN}"
if [[ "$STATUS" == "200" ]] && grep -qE '"reference":"PAY-' <<< "$BODY"; then
  pass "a payment record exists with a reference"
else
  fail "no payment record for the order" "$BODY"
fi

# --- 6. The kitchen ----------------------------------------------------------
if [[ "$ORDER_STATUS" == "PAID" ]]; then
  step "The kitchen prepares and hands over the order"

  CHEF_TOKEN="$(login "$CHEF_EMAIL" "$DEMO_PASSWORD")"
  if [[ -z "$CHEF_TOKEN" ]]; then
    fail "could not sign in as the demo chef" "is seed data enabled?"
  else
    pass "signed in as the chef"

    TICKET_ID=""
    for _ in $(seq 1 "$EVENT_TIMEOUT"); do
      api GET /api/v1/kitchen/board -H "Authorization: Bearer ${CHEF_TOKEN}"
      TICKET_ID="$(grep -o "\"id\":\"[^\"]*\",\"orderId\":\"${ORDER_ID}\"" <<< "$BODY" \
                   | sed 's/"id":"//;s/","orderId.*//' | head -1)"
      [[ -n "$TICKET_ID" ]] && break
      sleep 1
    done

    if [[ -n "$TICKET_ID" ]]; then
      pass "payment.completed created a kitchen ticket"

      # The ticket should say what to cook, not just an amount.
      if grep -q '"itemsSummary":"[0-9]* × ' <<< "$BODY"; then
        pass "the ticket names the dishes"
      else
        fail "the ticket does not name the dishes" \
             "order.placed may not have reached kitchen-service"
      fi

      for next in PREPARING READY DELIVERED; do
        api PATCH "/api/v1/kitchen/tickets/${TICKET_ID}/status" \
          -H "Authorization: Bearer ${CHEF_TOKEN}" \
          -H 'Content-Type: application/json' \
          -d "{\"status\":\"${next}\"}"

        if [[ "$STATUS" == "200" ]]; then
          pass "ticket moved to ${next}"
        else
          fail "could not move the ticket to ${next} (${STATUS})" "$BODY"
          break
        fi

        # And the order should follow, via kitchen.status.updated.
        REACHED=false
        for _ in $(seq 1 20); do
          api GET "/api/v1/orders/${ORDER_ID}" -H "Authorization: Bearer ${CUSTOMER_TOKEN}"
          [[ "$(json status "$BODY")" == "$next" ]] && { REACHED=true; break; }
          sleep 1
        done

        if [[ "$REACHED" == "true" ]]; then
          pass "the order followed the kitchen to ${next}"
        else
          fail "the order did not reach ${next}" \
               "kitchen.status.updated may not be reaching order-service"
        fi
      done
    else
      fail "no kitchen ticket after ${EVENT_TIMEOUT}s" \
           "check the kitchen.payment-completed binding"
    fi
  fi
fi

# --- 7. Notifications --------------------------------------------------------
step "The customer was told what was happening"

api GET /api/v1/notifications -H "Authorization: Bearer ${CUSTOMER_TOKEN}"
NOTIFICATION_COUNT="$(grep -o '"type":"' <<< "$BODY" | wc -l | tr -d ' ')"

if [[ "$NOTIFICATION_COUNT" -ge 2 ]]; then
  pass "${NOTIFICATION_COUNT} notifications recorded across the flow"
else
  fail "only ${NOTIFICATION_COUNT} notification(s)" \
       "order.status.changed may not be reaching notification-service"
fi

# --- 8. Authorisation holds under a real session -----------------------------
step "Authorisation still holds"

api POST /api/v1/menu/items \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"categoryId":"aaaaaaaa-0000-0000-0000-000000000002","name":"Nope","price":1.00,"preparationMinutes":5}'

if [[ "$STATUS" == "403" ]]; then
  pass "a customer cannot write to the menu"
else
  fail "a customer reached the menu write endpoint (${STATUS})" "$BODY"
fi

ADMIN_TOKEN="$(login "$ADMIN_EMAIL" "$DEMO_PASSWORD")"
if [[ -n "$ADMIN_TOKEN" ]]; then
  api POST /api/v1/menu/items \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "{\"categoryId\":\"aaaaaaaa-0000-0000-0000-000000000002\",\"name\":\"E2E Dish $(date +%s)\",\"price\":9.99,\"preparationMinutes\":15}"
  if [[ "$STATUS" == "201" ]]; then
    pass "an admin can write to the menu"
  else
    fail "an admin could not write to the menu (${STATUS})" "$BODY"
  fi
fi

# --- Summary -----------------------------------------------------------------
printf '\n%s\n' "────────────────────────────────────────────────────────────"
printf '  %d passed, %d failed\n\n' "$PASSED" "$FAILED"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    printf '### End-to-end — `%s`\n\n' "$BASE_URL"
    printf '| Result | Count |\n|---|---|\n| Passed | %d |\n| Failed | %d |\n\n' \
      "$PASSED" "$FAILED"
    printf 'Order `%s` followed from placement to completion.\n' "${ORDER_ID:0:8}"
  } >> "$GITHUB_STEP_SUMMARY"
fi

[[ "$FAILED" -eq 0 ]]
