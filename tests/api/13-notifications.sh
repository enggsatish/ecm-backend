#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# Test Suite 13: Notifications
# Verifies: notification list, unread count, mark read, preferences
# ═══════════════════════════════════════════════════════════════════════════════
source "$(dirname "$0")/helpers.sh"

ADMIN_TOKEN=$(get_state "ADMIN_JWT")
if [[ -z "$ADMIN_TOKEN" ]]; then log_skip "Suite 13: No admin token"; exit 0; fi

log_section "Notifications"

# ── Notification Count ────────────────────────────────────────────────────────
api_get "/api/notifications/count" "$ADMIN_TOKEN"
assert_status 200 "Get unread count"

# ── List Notifications ────────────────────────────────────────────────────────
api_get "/api/notifications" "$ADMIN_TOKEN"
assert_status 200 "List notifications"

# ── All Notifications ─────────────────────────────────────────────────────────
api_get "/api/notifications?all=true" "$ADMIN_TOKEN"
assert_status 200 "List all notifications (including read)"

# ── Preferences ───────────────────────────────────────────────────────────────
api_get "/api/notifications/preferences" "$ADMIN_TOKEN"
assert_status 200 "Get notification preferences"

# ── Mark All Read ─────────────────────────────────────────────────────────────
api_post "/api/notifications/read-all" "$ADMIN_TOKEN" ""
assert_status 200 "Mark all notifications read"
