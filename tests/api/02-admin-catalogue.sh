#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 02: Admin — Product Catalogue, Categories, Segments
# Verifies: CRUD operations on catalogue entities
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 02: No admin token"; exit 0; fi

log_section "Product Catalogue"

# ── Segments ──────────────────────────────────────────────────────────────────
api_get "/api/admin/segments" "$ADMIN_TOKEN"
assert_status 200 "List segments"
assert_json_length '.data' 3 "At least 3 segments (Retail, Commercial, SMB)"

# ── Product Lines ─────────────────────────────────────────────────────────────
api_get "/api/admin/product-lines" "$ADMIN_TOKEN"
assert_status 200 "List product lines"
assert_json_length '.data' 1 "At least 1 product line"

# ── Categories ────────────────────────────────────────────────────────────────
api_get "/api/admin/categories?flat=true" "$ADMIN_TOKEN"
assert_status 200 "List categories (flat)"
assert_json_length '.data' 5 "At least 5 categories"

# ── Products ──────────────────────────────────────────────────────────────────
api_get "/api/admin/products" "$ADMIN_TOKEN"
assert_status 200 "List products"
assert_json_length '.data' 1 "At least 1 product"

# ── Create Product with Document Types ────────────────────────────────────────
TIMESTAMP=$(date +%s)
api_post "/api/admin/products" "$ADMIN_TOKEN" "{
  \"productCode\": \"TEST_PROD_$TIMESTAMP\",
  \"displayName\": \"Test Product $TIMESTAMP\",
  \"description\": \"Automated test product\",
  \"productSchema\": {},
  \"segmentId\": 1,
  \"productLineId\": 2
}"
assert_status 201 "Create product"
assert_success "Create product — success envelope"

PRODUCT_ID=$(echo "$API_RESPONSE" | jq -r '.data.id')
save_state "TEST_PRODUCT_ID" "$PRODUCT_ID"
log_info "Created product ID: $PRODUCT_ID"

# ── Add Document Type to Product ──────────────────────────────────────────────
api_post "/api/admin/products/$PRODUCT_ID/document-types" "$ADMIN_TOKEN" "{
  \"name\": \"Test Government ID\",
  \"code\": \"TEST_GOV_ID_$TIMESTAMP\",
  \"categoryId\": 3,
  \"sourceType\": \"UPLOAD\",
  \"onUploadAction\": \"OCR_ONLY\",
  \"isRequired\": true,
  \"sortOrder\": 1
}"
assert_status 201 "Add document type to product"

api_post "/api/admin/products/$PRODUCT_ID/document-types" "$ADMIN_TOKEN" "{
  \"name\": \"Test Income Proof\",
  \"code\": \"TEST_INCOME_$TIMESTAMP\",
  \"categoryId\": 4,
  \"sourceType\": \"UPLOAD\",
  \"onUploadAction\": \"REVIEW_REQUIRED\",
  \"isRequired\": true,
  \"sortOrder\": 2
}"
assert_status 201 "Add second document type"

# ── Get Product Detail ────────────────────────────────────────────────────────
api_get "/api/admin/products/$PRODUCT_ID" "$ADMIN_TOKEN"
assert_status 200 "Get product detail"
assert_json_length '.data.documentTypes' 2 "Product has 2 document types"

# ── Hierarchy ─────────────────────────────────────────────────────────────────
api_get "/api/admin/hierarchy" "$ADMIN_TOKEN"
assert_status 200 "Get product hierarchy"

# ── Workflow Definitions ──────────────────────────────────────────────────────
api_get "/api/admin/products/workflow-definitions" "$ADMIN_TOKEN"
assert_status 200 "Get workflow definitions"
