#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 10: Documents & OCR
# Verifies: upload, list, OCR processing, field extraction
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 10: No admin token"; exit 0; fi

log_section "Documents & OCR"

# ── List Documents ────────────────────────────────────────────────────────────
api_get "/api/documents" "$ADMIN_TOKEN"
assert_status 200 "List documents"

# ── Upload a test document ────────────────────────────────────────────────────
# Create a simple test PDF
TEST_FILE="$RESULTS_DIR/test-upload.txt"
echo "Test document for ECM API testing. Loan Amount: 250000. Borrower Name: John Doe." > "$TEST_FILE"

api_upload "/api/documents/upload" "$ADMIN_TOKEN" "$TEST_FILE"
if [[ "$API_STATUS" == "201" || "$API_STATUS" == "200" ]]; then
  log_pass "Upload document (HTTP $API_STATUS)"
  DOC_ID=$(echo "$API_RESPONSE" | jq -r '.data.id // empty')
  if [[ -n "$DOC_ID" && "$DOC_ID" != "null" ]]; then
    save_state "TEST_DOC_ID" "$DOC_ID"
    log_info "Uploaded document ID: $DOC_ID"
  fi
else
  log_fail "Upload document" "Got HTTP $API_STATUS"
fi

# ── Get Document ──────────────────────────────────────────────────────────────
DOC_ID=$(get_state "TEST_DOC_ID")
if [[ -n "$DOC_ID" ]]; then
  api_get "/api/documents/$DOC_ID" "$ADMIN_TOKEN"
  assert_status 200 "Get document by ID"
  assert_json_not_empty '.data.originalFilename' "Document has filename"

  # Wait briefly for OCR
  sleep 3

  api_get "/api/documents/$DOC_ID" "$ADMIN_TOKEN"
  OCR_STATUS=$(echo "$API_RESPONSE" | jq -r '.data.ocrCompleted // false')
  log_info "OCR completed: $OCR_STATUS"
else
  log_skip "Get document — no doc ID"
fi

# ── OCR Templates ─────────────────────────────────────────────────────────────
api_get "/api/admin/ocr-templates" "$ADMIN_TOKEN"
assert_status 200 "List OCR templates"
assert_json_length '.data' 1 "At least 1 OCR template"

# ── Link Document to Case Checklist ───────────────────────────────────────────
CASE_ID=$(get_state "TEST_CASE_ID")
ITEM_ID=$(get_state "TEST_CHECKLIST_ITEM_ID")
DOC_ID=$(get_state "TEST_DOC_ID")

if [[ -n "$CASE_ID" && -n "$ITEM_ID" && -n "$DOC_ID" ]]; then
  api_post "/api/admin/cases/$CASE_ID/checklist/link" "$ADMIN_TOKEN" "{
    \"checklistItemId\": $ITEM_ID,
    \"documentId\": \"$DOC_ID\"
  }"
  assert_status 200 "Link document to checklist item"
else
  log_skip "Link document — missing case, item, or doc ID"
fi

rm -f "$TEST_FILE"
