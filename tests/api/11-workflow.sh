#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 11: Workflow Engine
# Verifies: templates, definitions, task inbox, SLA
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
REVIEWER_TOKEN=$(get_state "REVIEWER_JWT")

if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 11: No admin token"; exit 0; fi

log_section "Workflow Engine"

# ── Templates ─────────────────────────────────────────────────────────────────
api_get "/api/workflow/templates" "$ADMIN_TOKEN"
assert_status 200 "List workflow templates"
TEMPLATE_COUNT=$(echo "$API_RESPONSE" | jq '.data | length' 2>/dev/null)
log_info "Workflow templates: $TEMPLATE_COUNT"

# ── Definitions ───────────────────────────────────────────────────────────────
api_get "/api/workflow/definitions" "$ADMIN_TOKEN"
assert_status 200 "List workflow definitions"

# ── Instances ─────────────────────────────────────────────────────────────────
api_get "/api/workflow/instances?page=0&size=5" "$ADMIN_TOKEN"
assert_status 200 "List workflow instances"

# ── Task Inbox (admin) ────────────────────────────────────────────────────────
api_get "/api/workflow/tasks/my" "$ADMIN_TOKEN"
assert_status 200 "Admin task inbox"

# ── Task Inbox (reviewer) ─────────────────────────────────────────────────────
if [[ -n "$REVIEWER_TOKEN" ]]; then
  api_get "/api/workflow/tasks/my" "$REVIEWER_TOKEN"
  assert_status 200 "Reviewer task inbox"
fi

# ── Backoffice Queue ──────────────────────────────────────────────────────────
api_get "/api/workflow/tasks/pending" "$ADMIN_TOKEN"
assert_status 200 "Backoffice queue (pending tasks)"

# ── SLA Summary ───────────────────────────────────────────────────────────────
api_get "/api/workflow/sla/summary" "$ADMIN_TOKEN"
assert_status 200 "SLA summary"

# ── Timeline for document ────────────────────────────────────────────────────
DOC_ID=$(get_state "TEST_DOC_ID")
if [[ -n "$DOC_ID" ]]; then
  api_get "/api/workflow/timeline/document/$DOC_ID" "$ADMIN_TOKEN"
  assert_status 200 "Document timeline"
fi
