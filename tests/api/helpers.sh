#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# ECM API Test Helpers
# Assertion functions, auth, HTTP wrappers, output formatting
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# ── Counters ──────────────────────────────────────────────────────────────────
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
TOTAL_COUNT=0

# ── Output ────────────────────────────────────────────────────────────────────
log_pass()  { ((PASS_COUNT++)); ((TOTAL_COUNT++)); echo -e "  ${GREEN}✓${NC} $1"; }
log_fail()  { ((FAIL_COUNT++)); ((TOTAL_COUNT++)); echo -e "  ${RED}✗${NC} $1"; echo -e "    ${RED}→ $2${NC}"; }
log_skip()  { ((SKIP_COUNT++)); ((TOTAL_COUNT++)); echo -e "  ${YELLOW}○${NC} $1 ${YELLOW}(skipped)${NC}"; }
log_section() { echo -e "\n${BOLD}${BLUE}━━━ $1 ━━━${NC}"; }
log_info()  { echo -e "  ${CYAN}ℹ${NC} $1"; }

# ── Results directory ─────────────────────────────────────────────────────────
RESULTS_DIR="$(dirname "$0")/test-results"
mkdir -p "$RESULTS_DIR"

# ── Load config ───────────────────────────────────────────────────────────────
load_config() {
  local env_file="$(dirname "$0")/test.env"
  if [[ ! -f "$env_file" ]]; then
    echo -e "${RED}ERROR: test.env not found. Copy test.env.template to test.env and fill in your values.${NC}"
    exit 1
  fi
  set -a
  source "$env_file"
  set +a
}

# ── Auth ──────────────────────────────────────────────────────────────────────

# Get token for a role. Uses pre-generated tokens if available, otherwise ROPC.
get_token() {
  local role="$1" # ADMIN, SUPERADMIN, REVIEWER, BACKOFFICE
  local token_var="${role}_TOKEN"
  local token="${!token_var:-}"

  if [[ -n "$token" ]]; then
    echo "$token"
    return
  fi

  # Try Resource Owner Password Grant
  local user_var="${role}_USERNAME"
  local pass_var="${role}_PASSWORD"
  local username="${!user_var:-}"
  local password="${!pass_var:-}"

  if [[ -z "$username" || -z "$password" || -z "$OKTA_TOKEN_URL" ]]; then
    echo ""
    return
  fi

  local response
  response=$(curl -s -X POST "$OKTA_TOKEN_URL" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=$OKTA_CLIENT_ID" \
    -d "client_secret=${OKTA_CLIENT_SECRET:-}" \
    -d "username=$username" \
    -d "password=$password" \
    -d "scope=${OKTA_SCOPE:-openid profile email groups}" 2>/dev/null)

  echo "$response" | jq -r '.access_token // empty' 2>/dev/null
}

# ── HTTP Helpers ──────────────────────────────────────────────────────────────

# Generic API call: api_call METHOD URL [TOKEN] [BODY]
# Returns: HTTP response body. Sets API_STATUS to HTTP status code.
API_STATUS=0
API_RESPONSE=""

api_call() {
  local method="$1"
  local url="$2"
  local token="${3:-}"
  local body="${4:-}"
  local full_url="${ECM_GATEWAY_URL}${url}"

  local args=(-s -w '\n%{http_code}' -X "$method" "$full_url")
  args+=(-H "Accept: application/json")

  if [[ -n "$token" ]]; then
    args+=(-H "Authorization: Bearer $token")
  fi

  if [[ -n "$body" ]]; then
    args+=(-H "Content-Type: application/json")
    args+=(-d "$body")
  fi

  local raw
  raw=$(curl "${args[@]}" 2>/dev/null)

  # Last line is status code
  API_STATUS=$(echo "$raw" | tail -1)
  API_RESPONSE=$(echo "$raw" | sed '$d')
}

# Shorthand: GET endpoint with token
api_get() { api_call GET "$1" "$2"; }

# Shorthand: POST endpoint with token and body
api_post() { api_call POST "$1" "$2" "$3"; }

# Shorthand: PATCH
api_patch() { api_call PATCH "$1" "$2" "$3"; }

# Shorthand: DELETE
api_delete() { api_call DELETE "$1" "$2"; }

# Multipart upload
api_upload() {
  local url="$1"
  local token="$2"
  local file_path="$3"
  local full_url="${ECM_GATEWAY_URL}${url}"

  local raw
  raw=$(curl -s -w '\n%{http_code}' -X POST "$full_url" \
    -H "Authorization: Bearer $token" \
    -F "file=@$file_path" 2>/dev/null)

  API_STATUS=$(echo "$raw" | tail -1)
  API_RESPONSE=$(echo "$raw" | sed '$d')
}

# ── Assertions ────────────────────────────────────────────────────────────────

# Assert HTTP status code
assert_status() {
  local expected="$1"
  local test_name="$2"
  if [[ "$API_STATUS" == "$expected" ]]; then
    log_pass "$test_name"
  else
    log_fail "$test_name" "Expected HTTP $expected, got $API_STATUS"
    echo "$API_RESPONSE" | jq -r '.message // .' 2>/dev/null | head -3 | sed 's/^/    /' || true
  fi
}

# Assert response JSON field equals value
assert_json_eq() {
  local jq_path="$1"
  local expected="$2"
  local test_name="$3"
  local actual
  actual=$(echo "$API_RESPONSE" | jq -r "$jq_path" 2>/dev/null)
  if [[ "$actual" == "$expected" ]]; then
    log_pass "$test_name"
  else
    log_fail "$test_name" "Expected '$expected', got '$actual'"
  fi
}

# Assert response JSON field is not null/empty
assert_json_not_empty() {
  local jq_path="$1"
  local test_name="$2"
  local actual
  actual=$(echo "$API_RESPONSE" | jq -r "$jq_path" 2>/dev/null)
  if [[ -n "$actual" && "$actual" != "null" ]]; then
    log_pass "$test_name"
  else
    log_fail "$test_name" "Expected non-empty value at $jq_path"
  fi
}

# Assert response JSON field contains substring
assert_json_contains() {
  local jq_path="$1"
  local substring="$2"
  local test_name="$3"
  local actual
  actual=$(echo "$API_RESPONSE" | jq -r "$jq_path" 2>/dev/null)
  if [[ "$actual" == *"$substring"* ]]; then
    log_pass "$test_name"
  else
    log_fail "$test_name" "Expected '$jq_path' to contain '$substring', got '$actual'"
  fi
}

# Assert JSON array length
assert_json_length() {
  local jq_path="$1"
  local min_length="$2"
  local test_name="$3"
  local actual
  actual=$(echo "$API_RESPONSE" | jq "$jq_path | length" 2>/dev/null)
  if [[ "$actual" -ge "$min_length" ]]; then
    log_pass "$test_name (count: $actual)"
  else
    log_fail "$test_name" "Expected at least $min_length items, got $actual"
  fi
}

# Assert success envelope
assert_success() {
  local test_name="$1"
  assert_json_eq '.success' 'true' "$test_name"
}

# ── DB Helpers ────────────────────────────────────────────────────────────────

db_query() {
  local sql="$1"
  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "$sql" 2>/dev/null
}

# ── Mailpit Helpers ───────────────────────────────────────────────────────────

# Get latest email to a recipient
mailpit_latest() {
  local recipient="$1"
  curl -s "${ECM_MAILPIT_URL}/api/v1/search?query=to:${recipient}&limit=1" 2>/dev/null | jq -r '.messages[0] // empty'
}

# Delete all mailpit messages
mailpit_clear() {
  curl -s -X DELETE "${ECM_MAILPIT_URL}/api/v1/messages" 2>/dev/null > /dev/null
}

# ── RabbitMQ Helpers ──────────────────────────────────────────────────────────

rabbitmq_queue_count() {
  local queue="$1"
  curl -s -u "${RABBITMQ_USER}:${RABBITMQ_PASS}" \
    "${RABBITMQ_URL}/api/queues/${RABBITMQ_VHOST}/$queue" 2>/dev/null | jq -r '.messages // 0'
}

# ── Summary ───────────────────────────────────────────────────────────────────

print_summary() {
  echo ""
  echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
  echo -e "${BOLD} Test Summary${NC}"
  echo -e "${BOLD}═══════════════════════════════════════════════${NC}"
  echo -e "  ${GREEN}Passed:  $PASS_COUNT${NC}"
  echo -e "  ${RED}Failed:  $FAIL_COUNT${NC}"
  echo -e "  ${YELLOW}Skipped: $SKIP_COUNT${NC}"
  echo -e "  Total:   $TOTAL_COUNT"
  echo ""
  if [[ "$FAIL_COUNT" -gt 0 ]]; then
    echo -e "${RED}${BOLD}SOME TESTS FAILED${NC}"
    return 1
  else
    echo -e "${GREEN}${BOLD}ALL TESTS PASSED${NC}"
    return 0
  fi
}

# ── Stored test state (shared between test files) ─────────────────────────────
# Tests write IDs and values here so subsequent tests can reference them
STATE_FILE="$RESULTS_DIR/test-state.json"
echo '{}' > "$STATE_FILE"

save_state() {
  local key="$1" value="$2"
  local tmp=$(mktemp)
  jq --arg k "$key" --arg v "$value" '.[$k] = $v' "$STATE_FILE" > "$tmp" && mv "$tmp" "$STATE_FILE"
}

get_state() {
  local key="$1"
  jq -r --arg k "$key" '.[$k] // empty' "$STATE_FILE"
}
