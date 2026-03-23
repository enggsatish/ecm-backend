#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 09: Role-Based Access Control
# Verifies: SUPER_ADMIN vs ADMIN access, unauthorized access blocked
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
SUPERADMIN_TOKEN=$(get_state "SUPERADMIN_JWT")
REVIEWER_TOKEN=$(get_state "REVIEWER_JWT")
BACKOFFICE_TOKEN=$(get_state "BACKOFFICE_JWT")

log_section "Role-Based Access Control"

# ── SUPER_ADMIN can access users ──────────────────────────────────────────────
if [[ -n "$SUPERADMIN_TOKEN" ]]; then
  api_get "/api/admin/users" "$SUPERADMIN_TOKEN"
  assert_status 200 "SUPER_ADMIN can list users"

  api_get "/api/admin/departments" "$SUPERADMIN_TOKEN"
  assert_status 200 "SUPER_ADMIN can list departments"

  api_get "/api/admin/roles" "$SUPERADMIN_TOKEN"
  assert_status 200 "SUPER_ADMIN can list roles"
else
  log_skip "SUPER_ADMIN tests — no token"
fi

# ── ADMIN cannot access users (restricted to SUPER_ADMIN) ─────────────────────
if [[ -n "$ADMIN_TOKEN" ]]; then
  api_get "/api/admin/users" "$ADMIN_TOKEN"
  assert_status 403 "ADMIN cannot list users (403)"

  api_get "/api/admin/departments" "$ADMIN_TOKEN"
  assert_status 403 "ADMIN cannot list departments (403)"

  api_get "/api/admin/roles" "$ADMIN_TOKEN"
  assert_status 403 "ADMIN cannot list roles (403)"

  # ADMIN can still access products, customers, cases
  api_get "/api/admin/products" "$ADMIN_TOKEN"
  assert_status 200 "ADMIN can list products"

  api_get "/api/admin/customers" "$ADMIN_TOKEN"
  assert_status 200 "ADMIN can list customers"

  api_get "/api/admin/cases" "$ADMIN_TOKEN"
  assert_status 200 "ADMIN can list cases"
else
  log_skip "ADMIN access tests — no token"
fi

# ── REVIEWER access ───────────────────────────────────────────────────────────
if [[ -n "$REVIEWER_TOKEN" ]]; then
  api_get "/api/admin/cases" "$REVIEWER_TOKEN"
  assert_status 200 "REVIEWER can list cases"

  api_get "/api/workflow/tasks/my" "$REVIEWER_TOKEN"
  assert_status 200 "REVIEWER can access task inbox"

  api_get "/api/admin/products" "$REVIEWER_TOKEN"
  # Reviewer may or may not have access to products — depends on endpoint auth
  log_info "REVIEWER products access: HTTP $API_STATUS"
else
  log_skip "REVIEWER tests — no token"
fi

# ── BACKOFFICE access ─────────────────────────────────────────────────────────
if [[ -n "$BACKOFFICE_TOKEN" ]]; then
  api_get "/api/admin/cases" "$BACKOFFICE_TOKEN"
  assert_status 200 "BACKOFFICE can list cases"
else
  log_skip "BACKOFFICE tests — no token"
fi

# ── No token = 401 ────────────────────────────────────────────────────────────
api_get "/api/admin/cases" ""
assert_status 401 "No token returns 401"

api_get "/api/auth/me" ""
assert_status 401 "No token on /api/auth/me returns 401"
