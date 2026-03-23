#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# ECM Platform — API Test Runner
#
# Usage:
#   ./run-tests.sh              Run all test suites
#   ./run-tests.sh 04 05        Run specific suites (by number)
#   ./run-tests.sh --no-cleanup Skip cleanup
#   ./run-tests.sh --list       List available suites
#
# Prerequisites:
#   1. Copy test.env.template to test.env and fill in credentials
#   2. All services must be running
#   3. jq must be installed: brew install jq
# ═══════════════════════════════════════════════════════════════════════════════

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Check dependencies ────────────────────────────────────────────────────────
if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is required. Install with: brew install jq"
  exit 1
fi

if ! command -v curl &>/dev/null; then
  echo "ERROR: curl is required."
  exit 1
fi

# ── Parse args ────────────────────────────────────────────────────────────────
SKIP_CLEANUP=false
LIST_ONLY=false
SPECIFIC_SUITES=()

for arg in "$@"; do
  case "$arg" in
    --no-cleanup) SKIP_CLEANUP=true ;;
    --list) LIST_ONLY=true ;;
    [0-9][0-9]) SPECIFIC_SUITES+=("$arg") ;;
    *) echo "Unknown arg: $arg"; exit 1 ;;
  esac
done

# ── Available suites ──────────────────────────────────────────────────────────
SUITES=(
  "01-auth-health.sh"
  "02-admin-catalogue.sh"
  "03-customers.sh"
  "04-cases-crud.sh"
  "05-case-verification.sh"
  "06-case-request-docs.sh"
  "07-external-participants.sh"
  "08-overrides.sh"
  "09-roles-permissions.sh"
  "10-documents-ocr.sh"
  "11-workflow.sh"
  "12-eforms.sh"
  "13-notifications.sh"
  "14-admin-system.sh"
)

if [[ "$LIST_ONLY" == true ]]; then
  echo "Available test suites:"
  for s in "${SUITES[@]}"; do
    echo "  ${s%.sh}"
  done
  exit 0
fi

# ── Load config ───────────────────────────────────────────────────────────────
source "$SCRIPT_DIR/helpers.sh"
load_config

# ── Banner ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════${NC}"
echo -e "${BOLD}${BLUE}  ECM Platform — API Test Suite${NC}"
echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════${NC}"
echo -e "  Gateway: ${ECM_GATEWAY_URL}"
echo -e "  Time:    $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# ── Reset state ───────────────────────────────────────────────────────────────
mkdir -p "$RESULTS_DIR"
echo '{}' > "$STATE_FILE"

# ── Determine which suites to run ─────────────────────────────────────────────
RUN_SUITES=()
if [[ ${#SPECIFIC_SUITES[@]} -gt 0 ]]; then
  for num in "${SPECIFIC_SUITES[@]}"; do
    for s in "${SUITES[@]}"; do
      if [[ "$s" == "$num-"* ]]; then
        RUN_SUITES+=("$s")
      fi
    done
  done
  # Always run auth first if running specific suites
  if [[ ! " ${RUN_SUITES[*]} " =~ " 01-auth-health.sh " ]]; then
    RUN_SUITES=("01-auth-health.sh" "${RUN_SUITES[@]}")
  fi
else
  RUN_SUITES=("${SUITES[@]}")
fi

# ── Run suites ────────────────────────────────────────────────────────────────
START_TIME=$(date +%s)

for suite in "${RUN_SUITES[@]}"; do
  if [[ -f "$SCRIPT_DIR/$suite" ]]; then
    source "$SCRIPT_DIR/$suite"
  else
    echo -e "${YELLOW}Suite not found: $suite${NC}"
  fi
done

# ── Cleanup ───────────────────────────────────────────────────────────────────
if [[ "$SKIP_CLEANUP" != true && -f "$SCRIPT_DIR/15-cleanup.sh" ]]; then
  source "$SCRIPT_DIR/15-cleanup.sh"
fi

# ── Results ───────────────────────────────────────────────────────────────────
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo ""
echo -e "  Duration: ${DURATION}s"
print_summary

# Save results to file
{
  echo "ECM API Test Results — $(date)"
  echo "Gateway: $ECM_GATEWAY_URL"
  echo "Duration: ${DURATION}s"
  echo "Passed: $PASS_COUNT / Failed: $FAIL_COUNT / Skipped: $SKIP_COUNT / Total: $TOTAL_COUNT"
} > "$RESULTS_DIR/latest-results.txt"
