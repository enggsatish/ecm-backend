#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 01: Auth & Health Checks
# Verifies: gateway is up, services are healthy, tokens work for each role
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

log_section "Auth & Health Checks"

# ── Gateway health ────────────────────────────────────────────────────────────
api_call GET "/actuator/health" ""
assert_status 200 "Gateway health endpoint"

# ── Auth for each role ────────────────────────────────────────────────────────
for role in ADMIN SUPERADMIN REVIEWER BACKOFFICE; do
  token=$(get_token "$role")
  if [[ -z "$token" ]]; then
    log_skip "Auth: $role — no token configured"
    continue
  fi
  save_state "${role}_JWT" "$token"

  api_get "/api/auth/me" "$token"
  assert_status 200 "Auth: $role — /api/auth/me returns 200"
  assert_json_not_empty '.data.email' "Auth: $role — has email"
  assert_json_not_empty '.data.roles' "Auth: $role — has roles"

  local email=$(echo "$API_RESPONSE" | jq -r '.data.email // empty')
  save_state "${role}_EMAIL" "$email"
  log_info "$role authenticated as $email"
done
