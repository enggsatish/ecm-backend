#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 14: Admin System — Config, Audit, Retention
# Verifies: tenant config, audit log, retention policies
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 14: No admin token"; exit 0; fi

log_section "Admin System"

# ── Tenant Config ─────────────────────────────────────────────────────────────
api_get "/api/admin/config" "$ADMIN_TOKEN"
assert_status 200 "Get tenant config"

# ── Audit Log ─────────────────────────────────────────────────────────────────
api_get "/api/admin/audit?page=0&size=5" "$ADMIN_TOKEN"
assert_status 200 "Get audit log"

# ── Retention Policies ────────────────────────────────────────────────────────
api_get "/api/admin/retention-policies" "$ADMIN_TOKEN"
assert_status 200 "List retention policies"

# ── DocuSign Config ───────────────────────────────────────────────────────────
api_get "/api/admin/integrations/docusign" "$ADMIN_TOKEN"
assert_status 200 "Get DocuSign config"
