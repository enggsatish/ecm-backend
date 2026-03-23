#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 05: Case Verification & Assignment
# Verifies: verify checklist items, assign case, claim case
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
REVIEWER_TOKEN=$(get_state "REVIEWER_JWT")
CASE_ID=$(get_state "TEST_CASE_ID")

if [[ -z "$ADMIN_TOKEN" || -z "$CASE_ID" ]]; then
  log_skip "Suite 05: No admin token or case ID"
  exit 0
fi

log_section "Case Verification & Assignment"

# ── Get checklist items ───────────────────────────────────────────────────────
api_get "/api/admin/cases/$CASE_ID" "$ADMIN_TOKEN"
ITEM_IDS=$(echo "$API_RESPONSE" | jq -r '[.data.checklist[].id] | join(",")' 2>/dev/null)
ITEM_ID_ARRAY=$(echo "$API_RESPONSE" | jq '[.data.checklist[].id]' 2>/dev/null)

if [[ -z "$ITEM_IDS" || "$ITEM_IDS" == "null" ]]; then
  log_skip "No checklist items to verify"
  exit 0
fi

log_info "Checklist items: $ITEM_IDS"

# ── Verify Items (save verification) ──────────────────────────────────────────
api_post "/api/admin/cases/$CASE_ID/verify" "$ADMIN_TOKEN" "{
  \"verifiedItemIds\": $ITEM_ID_ARRAY
}"
assert_status 200 "Save verification — all items checked"

# Check if case auto-transitioned to UNDER_REVIEW
CASE_STATUS=$(echo "$API_RESPONSE" | jq -r '.data.status')
log_info "Case status after verification: $CASE_STATUS"

# ── Assign to Group ───────────────────────────────────────────────────────────
api_post "/api/admin/cases/$CASE_ID/assign" "$ADMIN_TOKEN" "{
  \"assignToGroup\": \"ECM_REVIEWER\",
  \"comment\": \"Assigning for review\"
}"
assert_status 200 "Assign case to ECM_REVIEWER group"
assert_json_eq '.data.assignedToGroup' 'ECM_REVIEWER' "Assigned to group"

# ── Claim (as reviewer) ──────────────────────────────────────────────────────
if [[ -n "$REVIEWER_TOKEN" ]]; then
  api_post "/api/admin/cases/$CASE_ID/claim" "$REVIEWER_TOKEN"
  assert_status 200 "Claim case as reviewer"
  assert_json_not_empty '.data.claimedBy' "Case has claimedBy after claim"
else
  log_skip "Claim case — no reviewer token"
fi

# ── Reassign to Person ────────────────────────────────────────────────────────
ADMIN_EMAIL=$(get_state "ADMIN_EMAIL")
api_post "/api/admin/cases/$CASE_ID/assign" "$ADMIN_TOKEN" "{
  \"assignTo\": \"$ADMIN_EMAIL\",
  \"assignToName\": \"Admin User\",
  \"comment\": \"Reassigning back to admin\"
}"
assert_status 200 "Reassign case to admin person"
assert_json_eq '.data.assignedTo' "$ADMIN_EMAIL" "Assigned to admin email"

# ── Timeline ──────────────────────────────────────────────────────────────────
api_get "/api/admin/cases/$CASE_ID/timeline" "$ADMIN_TOKEN"
assert_status 200 "Get case timeline"
assert_json_length '.data' 2 "Timeline has at least 2 events"
