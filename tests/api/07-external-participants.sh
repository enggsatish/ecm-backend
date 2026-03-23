#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 07: External Participants
# Verifies: add participant, share docs, OTP flow, external comment
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
CASE_ID=$(get_state "TEST_CASE_ID")

if [[ -z "$ADMIN_TOKEN" || -z "$CASE_ID" ]]; then
  log_skip "Suite 07: No admin token or case ID"
  exit 0
fi

log_section "External Participants"

# ── Add Participant ───────────────────────────────────────────────────────────
api_post "/api/admin/cases/$CASE_ID/participants" "$ADMIN_TOKEN" "{
  \"name\": \"$TEST_EXTERNAL_NAME\",
  \"email\": \"$TEST_EXTERNAL_EMAIL\",
  \"organization\": \"$TEST_EXTERNAL_ORG\",
  \"role\": \"$TEST_EXTERNAL_ROLE\",
  \"phone\": \"+1-555-0100\"
}"
assert_status 201 "Add external participant"
assert_json_eq '.data.role' "$TEST_EXTERNAL_ROLE" "Participant role matches"
assert_json_not_empty '.data.accessToken' "Access token generated"

ACCESS_TOKEN=$(echo "$API_RESPONSE" | jq -r '.data.accessToken')
PARTICIPANT_ID=$(echo "$API_RESPONSE" | jq -r '.data.id')
save_state "TEST_ACCESS_TOKEN" "$ACCESS_TOKEN"
save_state "TEST_PARTICIPANT_ID" "$PARTICIPANT_ID"
log_info "Access token: $ACCESS_TOKEN"

# ── List Participants ─────────────────────────────────────────────────────────
api_get "/api/admin/cases/$CASE_ID/participants" "$ADMIN_TOKEN"
assert_status 200 "List participants"
assert_json_length '.data' 1 "1 participant exists"

# ── Share Documents ───────────────────────────────────────────────────────────
CHECKLIST_ITEM_ID=$(get_state "TEST_CHECKLIST_ITEM_ID")
if [[ -n "$CHECKLIST_ITEM_ID" ]]; then
  api_post "/api/admin/cases/$CASE_ID/participants/share" "$ADMIN_TOKEN" "{
    \"participantId\": $PARTICIPANT_ID,
    \"caseDocumentIds\": [$CHECKLIST_ITEM_ID]
  }"
  assert_status 200 "Share documents with participant"
else
  log_skip "Share documents — no checklist item"
fi

log_section "External OTP Flow (no JWT)"

# ── Request OTP ───────────────────────────────────────────────────────────────
api_call POST "/api/admin/cases/external/$ACCESS_TOKEN/request-otp" ""
assert_status 200 "Request OTP — no JWT required"

# ── Get OTP from DB (since email may not be delivered in test) ────────────────
OTP_CODE=$(db_query "SELECT otp_code FROM ecm_core.external_participants WHERE access_token = '$ACCESS_TOKEN'")
if [[ -n "$OTP_CODE" ]]; then
  log_pass "OTP generated in DB: $OTP_CODE"
  save_state "TEST_OTP" "$OTP_CODE"
else
  log_fail "OTP generation" "No OTP found in DB"
  exit 0
fi

# ── Verify OTP ────────────────────────────────────────────────────────────────
api_call POST "/api/admin/cases/external/$ACCESS_TOKEN/verify-otp" "" "{
  \"email\": \"$TEST_EXTERNAL_EMAIL\",
  \"otp\": \"$OTP_CODE\"
}"
assert_status 200 "Verify OTP — access granted"
assert_json_not_empty '.data.caseId' "External view has caseId"
assert_json_not_empty '.data.participantName' "External view has participant name"
assert_json_eq '.data.participantRole' "$TEST_EXTERNAL_ROLE" "External view role matches"

# ── Invalid OTP ───────────────────────────────────────────────────────────────
api_call POST "/api/admin/cases/external/$ACCESS_TOKEN/verify-otp" "" "{
  \"email\": \"$TEST_EXTERNAL_EMAIL\",
  \"otp\": \"000000\"
}"
assert_status 401 "Invalid OTP returns 401"

# ── External Comment ──────────────────────────────────────────────────────────
# Re-generate OTP since verify clears it
api_call POST "/api/admin/cases/external/$ACCESS_TOKEN/request-otp" ""
OTP_CODE=$(db_query "SELECT otp_code FROM ecm_core.external_participants WHERE access_token = '$ACCESS_TOKEN'")

api_call POST "/api/admin/cases/external/$ACCESS_TOKEN/comment" "" "{
  \"otp\": \"$OTP_CODE\",
  \"comment\": \"Automated test comment from external participant\"
}"
assert_status 200 "External comment — added successfully"

# ── Verify comment appears in case notes ──────────────────────────────────────
api_get "/api/admin/cases/$CASE_ID" "$ADMIN_TOKEN"
NOTES=$(echo "$API_RESPONSE" | jq -r '.data.metadata.notes // []')
log_info "Case notes count: $(echo "$NOTES" | jq length 2>/dev/null || echo 0)"

# ── Remove Participant ────────────────────────────────────────────────────────
api_delete "/api/admin/cases/$CASE_ID/participants/$PARTICIPANT_ID" "$ADMIN_TOKEN"
assert_status 200 "Remove participant"

# ── Verify removed participant can't access ───────────────────────────────────
api_call POST "/api/admin/cases/external/$ACCESS_TOKEN/request-otp" ""
assert_status 403 "Removed participant gets 403"

# ── Check Mailpit for emails ──────────────────────────────────────────────────
log_section "Email Verification"
MAIL_DATA=$(curl -s "${ECM_MAILPIT_URL}/api/v1/messages?limit=10" 2>/dev/null)
MAIL_COUNT=$(echo "$MAIL_DATA" | jq '.total // 0' 2>/dev/null)
if [[ "$MAIL_COUNT" -gt 0 ]]; then
  log_pass "Mailpit has $MAIL_COUNT email(s)"
  # Check for OTP email
  OTP_MAIL=$(echo "$MAIL_DATA" | jq -r '.messages[] | select(.Subject | contains("Verification")) | .ID // empty' 2>/dev/null | head -1)
  if [[ -n "$OTP_MAIL" ]]; then
    log_pass "OTP email found in Mailpit"
  else
    log_info "OTP email not found — batch processor may not have run yet (5 min interval)"
  fi
else
  log_info "No emails in Mailpit — batch processor may not have run yet"
fi
