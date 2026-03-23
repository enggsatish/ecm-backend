#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 06: Request Additional Documents
# Verifies: reviewer requests docs, new items added, case status changes
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
CASE_ID=$(get_state "TEST_CASE_ID")

if [[ -z "$ADMIN_TOKEN" || -z "$CASE_ID" ]]; then
  log_skip "Suite 06: No admin token or case ID"
  exit 0
fi

log_section "Request Additional Documents"

# ── Get initial checklist count ───────────────────────────────────────────────
api_get "/api/admin/cases/$CASE_ID" "$ADMIN_TOKEN"
INITIAL_COUNT=$(echo "$API_RESPONSE" | jq '.data.checklist | length')
log_info "Initial checklist items: $INITIAL_COUNT"

# ── Get available categories ──────────────────────────────────────────────────
api_get "/api/admin/categories?flat=true" "$ADMIN_TOKEN"
FIRST_CAT_ID=$(echo "$API_RESPONSE" | jq -r '.data[0].id // empty')
SECOND_CAT_ID=$(echo "$API_RESPONSE" | jq -r '.data[1].id // empty')

if [[ -z "$FIRST_CAT_ID" ]]; then
  log_skip "No categories available for request docs test"
  exit 0
fi

# ── Request Additional Docs ───────────────────────────────────────────────────
api_post "/api/admin/cases/$CASE_ID/request-docs" "$ADMIN_TOKEN" "{
  \"categoryIds\": [$FIRST_CAT_ID, $SECOND_CAT_ID],
  \"comment\": \"Need updated financial documents\",
  \"reassignTo\": null
}"
assert_status 200 "Request additional docs"
assert_json_eq '.data.status' 'DOCUMENTS_PENDING' "Case moved to DOCUMENTS_PENDING"

NEW_COUNT=$(echo "$API_RESPONSE" | jq '.data.checklist | length')
EXPECTED=$((INITIAL_COUNT + 2))
if [[ "$NEW_COUNT" -ge "$EXPECTED" ]]; then
  log_pass "Checklist grew from $INITIAL_COUNT to $NEW_COUNT items"
else
  log_fail "Checklist growth" "Expected at least $EXPECTED items, got $NEW_COUNT"
fi
