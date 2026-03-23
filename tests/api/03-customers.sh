#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 03: Customer Management
# Verifies: CRUD customers, product enrollments
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 03: No admin token"; exit 0; fi

log_section "Customer Management"

# ── List Customers ────────────────────────────────────────────────────────────
api_get "/api/admin/customers" "$ADMIN_TOKEN"
assert_status 200 "List customers"

# ── Create Customer ───────────────────────────────────────────────────────────
TIMESTAMP=$(date +%s)
api_post "/api/admin/customers" "$ADMIN_TOKEN" "{
  \"externalId\": \"TEST-CUST-$TIMESTAMP\",
  \"displayName\": \"Test Customer $TIMESTAMP\",
  \"partyType\": \"RETAIL\",
  \"segmentId\": 1,
  \"shortName\": \"TestCust\"
}"
assert_status 201 "Create customer"

CUSTOMER_ID=$(echo "$API_RESPONSE" | jq -r '.data.id')
save_state "TEST_CUSTOMER_ID" "$CUSTOMER_ID"
save_state "TEST_CUSTOMER_EXT_ID" "TEST-CUST-$TIMESTAMP"
log_info "Created customer ID: $CUSTOMER_ID"

# ── Get Customer Detail ───────────────────────────────────────────────────────
api_get "/api/admin/customers/$CUSTOMER_ID" "$ADMIN_TOKEN"
assert_status 200 "Get customer detail"
assert_json_eq '.data.displayName' "Test Customer $TIMESTAMP" "Customer name matches"

# ── Add Product Enrollment ────────────────────────────────────────────────────
PRODUCT_ID=$(get_state "TEST_PRODUCT_ID")
if [[ -n "$PRODUCT_ID" ]]; then
  api_post "/api/admin/customers/$CUSTOMER_ID/enrollments" "$ADMIN_TOKEN" "{
    \"productLineId\": 2,
    \"productId\": $PRODUCT_ID
  }"
  assert_status 201 "Add product enrollment"

  # Verify enrollment appears
  api_get "/api/admin/customers/$CUSTOMER_ID" "$ADMIN_TOKEN"
  assert_json_length '.data.enrollments' 1 "Customer has 1 enrollment"
else
  log_skip "Add enrollment — no test product"
fi

# ── Search Customer ───────────────────────────────────────────────────────────
api_get "/api/admin/customers?q=Test+Customer" "$ADMIN_TOKEN"
assert_status 200 "Search customer by name"
