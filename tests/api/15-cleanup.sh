#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 15: Cleanup
# Cleans up test data created during the test run
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 15: No admin token"; exit 0; fi

log_section "Cleanup"

# ── Cancel test case ──────────────────────────────────────────────────────────
CASE_ID=$(get_state "TEST_CASE_ID")
if [[ -n "$CASE_ID" ]]; then
  api_post "/api/admin/cases/$CASE_ID/cancel" "$ADMIN_TOKEN"
  if [[ "$API_STATUS" == "200" ]]; then
    log_pass "Test case cancelled"
  else
    log_info "Case cancel returned $API_STATUS (may already be cancelled)"
  fi
fi

# ── Deactivate test product ───────────────────────────────────────────────────
PRODUCT_ID=$(get_state "TEST_PRODUCT_ID")
if [[ -n "$PRODUCT_ID" ]]; then
  api_delete "/api/admin/products/$PRODUCT_ID" "$ADMIN_TOKEN"
  if [[ "$API_STATUS" == "200" ]]; then
    log_pass "Test product deactivated"
  else
    log_info "Product deactivate returned $API_STATUS"
  fi
fi

# ── Deactivate test customer ──────────────────────────────────────────────────
CUSTOMER_ID=$(get_state "TEST_CUSTOMER_ID")
if [[ -n "$CUSTOMER_ID" ]]; then
  api_delete "/api/admin/customers/$CUSTOMER_ID" "$ADMIN_TOKEN"
  if [[ "$API_STATUS" == "200" ]]; then
    log_pass "Test customer deactivated"
  else
    log_info "Customer deactivate returned $API_STATUS"
  fi
fi

log_info "Test state file: $(cat "$STATE_FILE" | jq -c '.' 2>/dev/null)"
