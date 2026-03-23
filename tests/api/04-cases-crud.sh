#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 04: Case CRUD + Search
# Verifies: create case, get case, search, status filter, checklist population
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 04: No admin token"; exit 0; fi

log_section "Case CRUD & Search"

CUSTOMER_ID=$(get_state "TEST_CUSTOMER_ID")
PRODUCT_ID=$(get_state "TEST_PRODUCT_ID")

if [[ -z "$CUSTOMER_ID" || -z "$PRODUCT_ID" ]]; then
  # Fall back to seed data
  CUSTOMER_ID=$(db_query "SELECT id FROM ecm_core.parties WHERE external_id = '$TEST_CUSTOMER_EXTERNAL_ID' LIMIT 1")
  PRODUCT_ID="$TEST_PRODUCT_ID"
fi

# ── Create Case ───────────────────────────────────────────────────────────────
TIMESTAMP=$(date +%s)
api_post "/api/admin/cases" "$ADMIN_TOKEN" "{
  \"partyId\": \"$CUSTOMER_ID\",
  \"productId\": $PRODUCT_ID,
  \"caseType\": \"LOAN_ORIGINATION\",
  \"externalRef\": \"TEST-$TIMESTAMP\"
}"
assert_status 201 "Create case"
assert_success "Create case — success envelope"

CASE_ID=$(echo "$API_RESPONSE" | jq -r '.data.id')
save_state "TEST_CASE_ID" "$CASE_ID"
log_info "Created case ID: $CASE_ID"

# ── Get Case Detail ───────────────────────────────────────────────────────────
api_get "/api/admin/cases/$CASE_ID" "$ADMIN_TOKEN"
assert_status 200 "Get case detail"
assert_json_eq '.data.status' 'OPEN' "Case status is OPEN"
assert_json_not_empty '.data.partyDisplayName' "Case has customer name"
assert_json_not_empty '.data.productName' "Case has product name"

# Check checklist populated
CHECKLIST_COUNT=$(echo "$API_RESPONSE" | jq '.data.checklist | length')
if [[ "$CHECKLIST_COUNT" -gt 0 ]]; then
  log_pass "Checklist auto-populated ($CHECKLIST_COUNT items)"
  FIRST_ITEM_ID=$(echo "$API_RESPONSE" | jq -r '.data.checklist[0].id')
  save_state "TEST_CHECKLIST_ITEM_ID" "$FIRST_ITEM_ID"
else
  log_info "Checklist is empty (product may have no document types)"
fi

# ── List Cases ────────────────────────────────────────────────────────────────
api_get "/api/admin/cases" "$ADMIN_TOKEN"
assert_status 200 "List all cases"
assert_json_length '.data' 1 "At least 1 case exists"

# ── Search by Ref ─────────────────────────────────────────────────────────────
api_get "/api/admin/cases?search=TEST-$TIMESTAMP" "$ADMIN_TOKEN"
assert_status 200 "Search by external ref"
assert_json_length '.data' 1 "Search returns 1 result"

# ── Search by Customer Name ───────────────────────────────────────────────────
api_get "/api/admin/cases?search=$TEST_CUSTOMER_NAME" "$ADMIN_TOKEN"
assert_status 200 "Search by customer name"

# ── Filter by Status ──────────────────────────────────────────────────────────
api_get "/api/admin/cases?status=OPEN" "$ADMIN_TOKEN"
assert_status 200 "Filter by status OPEN"

# ── Filter by Case Type ──────────────────────────────────────────────────────
api_get "/api/admin/cases?caseType=LOAN_ORIGINATION" "$ADMIN_TOKEN"
assert_status 200 "Filter by case type"

# ── Add Note ──────────────────────────────────────────────────────────────────
api_post "/api/admin/cases/$CASE_ID/notes" "$ADMIN_TOKEN" "{\"note\": \"Automated test note $TIMESTAMP\"}"
assert_status 200 "Add case note"

# ── Status Transition ─────────────────────────────────────────────────────────
api_patch "/api/admin/cases/$CASE_ID/status" "$ADMIN_TOKEN" "{\"status\": \"DOCUMENTS_PENDING\", \"comment\": \"Auto test\"}"
assert_status 200 "Transition: OPEN → DOCUMENTS_PENDING"
assert_json_eq '.data.status' 'DOCUMENTS_PENDING' "Status is now DOCUMENTS_PENDING"
