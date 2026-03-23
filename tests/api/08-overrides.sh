#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 08: Override System
# Verifies: request override, review override, admin bypass
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
CASE_ID=$(get_state "TEST_CASE_ID")
ITEM_ID=$(get_state "TEST_CHECKLIST_ITEM_ID")

if [[ -z "$ADMIN_TOKEN" || -z "$CASE_ID" || -z "$ITEM_ID" ]]; then
  log_skip "Suite 08: Missing admin token, case ID, or checklist item"
  exit 0
fi

log_section "Override System"

# ── Request Override ──────────────────────────────────────────────────────────
api_post "/api/admin/cases/$CASE_ID/checklist/$ITEM_ID/override-request" "$ADMIN_TOKEN" "{
  \"reason\": \"Customer provided verbal confirmation\"
}"
assert_status 200 "Request override"
assert_json_not_empty '.data.id' "Override request has ID"

OVERRIDE_ID=$(echo "$API_RESPONSE" | jq -r '.data.id')
save_state "TEST_OVERRIDE_ID" "$OVERRIDE_ID"

# ── List Override Requests ────────────────────────────────────────────────────
api_get "/api/admin/override-requests?caseId=$CASE_ID" "$ADMIN_TOKEN"
assert_status 200 "List override requests"
assert_json_length '.data' 1 "1 override request"
assert_json_eq '.data[0].status' 'PENDING' "Override is PENDING"

# ── Review Override (Approve) ─────────────────────────────────────────────────
api_post "/api/admin/override-requests/$OVERRIDE_ID/review" "$ADMIN_TOKEN" "{
  \"decision\": \"APPROVED\",
  \"reason\": \"Verbal confirmation accepted\"
}"
assert_status 200 "Approve override"
assert_json_eq '.data.status' 'APPROVED' "Override status is APPROVED"

# ── Admin Bypass on another item ──────────────────────────────────────────────
# Get a second checklist item
api_get "/api/admin/cases/$CASE_ID" "$ADMIN_TOKEN"
SECOND_ITEM_ID=$(echo "$API_RESPONSE" | jq -r '.data.checklist[1].id // empty')

if [[ -n "$SECOND_ITEM_ID" && "$SECOND_ITEM_ID" != "null" ]]; then
  api_post "/api/admin/cases/$CASE_ID/checklist/$SECOND_ITEM_ID/admin-bypass" "$ADMIN_TOKEN" "{
    \"reason\": \"Document verified offline\"
  }"
  assert_status 200 "Admin bypass checklist item"
else
  log_skip "Admin bypass — no second checklist item"
fi
