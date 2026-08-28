#!/usr/bin/env bash
# Import/FQN boundary gate — the second net beside ArchUnit (design.md §3.3, AGENTS.md §2).
# Runs in CI before the test suite; fails fast with a readable violation report.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0

check() {
  local desc="$1" pattern="$2" path="$3"
  local matches
  matches=$(grep -rEn "$pattern" "$path" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    echo "$matches" | head -20
    echo "BOUNDARY VIOLATION: $desc (see AGENTS.md §2)" >&2
    fail=1
  fi
}

# Cross-module imports are forbidden — communication is events only (outbox → SNS → SQS).
check "payments importing ledger/notifications" 'import io\.dargent\.(ledger|notifications)\.' modules/payments/src/main/java
check "ledger importing payments/notifications" 'import io\.dargent\.(payments|notifications)\.' modules/ledger/src/main/java
check "notifications importing payments/ledger" 'import io\.dargent\.(payments|ledger)\.' modules/notifications/src/main/java
check "shared importing business modules" 'import io\.dargent\.(payments|ledger|notifications)\.' modules/shared/src/main/java
check "psp-simulator importing platform modules (it is the outside world)" 'import io\.dargent\.(shared|payments|ledger|notifications)\.' apps/psp-simulator/src

# Domain purity: framework types never enter domain packages (coding-standards §2).
domain_files=$(find modules -type f -name '*.java' -path '*/domain/*' 2>/dev/null || true)
if [ -n "$domain_files" ]; then
  matches=$(echo "$domain_files" | xargs grep -En 'import (org\.springframework|jakarta\.persistence|com\.fasterxml\.jackson|tools\.jackson|software\.amazon\.awssdk)' 2>/dev/null || true)
  if [ -n "$matches" ]; then
    echo "$matches" | head -20
    echo "BOUNDARY VIOLATION: framework import inside a domain package (coding-standards §2)" >&2
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "check-boundaries: FAILED" >&2
  exit 1
fi
echo "check-boundaries: OK"
