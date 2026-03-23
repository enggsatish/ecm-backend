#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 12: eForms
# Verifies: form definitions, submissions, my submissions
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 12: No admin token"; exit 0; fi

log_section "eForms"

# ── List Published Forms ──────────────────────────────────────────────────────
api_get "/api/eforms/render" "$ADMIN_TOKEN"
assert_status 200 "List published forms"
FORM_COUNT=$(echo "$API_RESPONSE" | jq '.data | length' 2>/dev/null || echo 0)
log_info "Published forms: $FORM_COUNT"

# ── List Form Definitions ────────────────────────────────────────────────────
api_get "/api/eforms/definitions" "$ADMIN_TOKEN"
assert_status 200 "List form definitions"

# ── My Submissions ────────────────────────────────────────────────────────────
api_get "/api/eforms/submissions/mine?page=0&size=5" "$ADMIN_TOKEN"
assert_status 200 "My form submissions"

# ── Submission Queue ──────────────────────────────────────────────────────────
api_get "/api/eforms/submissions/queue?page=0&size=5" "$ADMIN_TOKEN"
assert_status 200 "Form submission queue"
