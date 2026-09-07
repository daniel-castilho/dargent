#!/usr/bin/env bash
#
# Dargent coverage floors gate (E13 S1, design.md §11.1 + testing-playbook §5).
# Runs AFTER ./mvnw verify: enforces each module's owner-fixed LINE floor against the
# AGGREGATED exec (apps/api merge-aggregate output) so hexagonal ITs living in the boot app
# count toward the owning module. Floors (line ratio, combined unit+IT):
#   payments 0.70 · ledger 0.75 · shared 0.80 · notifications 0.50 · api 0.40
# psp-simulator: no floor (not in the initial owner-fixed set).
# Failure names the module + measured ratio. Never edit floors here (P1) — they live in the poms.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

AGG="$(pwd)/apps/api/target/coverage-aggregate.exec"
[[ -f "$AGG" ]] || { echo "COVERAGE FAIL: $AGG missing — run ./mvnw -B verify first"; exit 1; }

fail=0
while read -r module; do
    floor=$(grep -oE '<jacoco\.line\.floor>[0-9.]+</jacoco\.line\.floor>' "$module/pom.xml" \
        | head -1 | sed -E 's#.*>([0-9.]+)<.*#\1#')
    [[ -n "$floor" ]] || { echo "COVERAGE FAIL  $module: no jacoco.line.floor in its pom (P1 — floors live in module poms)"; fail=1; continue; }
    out=$(./mvnw -B jacoco:check@jacoco-check -pl "$module" \
        -Djacoco.check.data="$AGG" -Djacoco.line.floor="$floor" -Djacoco.check.skip=false 2>&1 || true)
    ratio=$( { grep -o 'covered ratio is [0-9.]*' <<<"$out" || true; } | awk '{print $4}' | head -1)
    if grep -q "All coverage checks have been met" <<<"$out"; then
        echo "COVERAGE PASS  $module floor=$floor"
    else
        echo "COVERAGE FAIL  $module floor=$floor measured=${ratio:-unknown}"
        fail=1
    fi
done <<'MODULES'
modules/payments
modules/ledger
modules/shared
modules/notifications
apps/api
MODULES

if [[ "$fail" -ne 0 ]]; then exit 1; fi
echo "COVERAGE: all floors met (combined unit+IT, aggregate exec)"
