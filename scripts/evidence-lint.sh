#!/usr/bin/env bash
#
# E13 R1 — evidence-lint: every cited CI run id must be real and must carry its run number.
#
# Sources (contract §3 R1): docs/epics.md (whole) + the §10 acceptance-matrix section of each
# tasks/<epic>/*-spec.md. For each source, assert:
#   (a) every backtick-quoted `3[0-9]{9,}` run id resolves via `gh api repos/<repo>/actions/runs/<id>` (200),
#   (b) every id has a run-number reference (`run #N` / `runs #N` / bare `#N`) ADJACENT, i.e. within
#       ±1 line of the id's line.
# Output: `file:line: violation` (P6 — evidence discipline; never silent). Exit 1 on any violation.
#
# Grandfathering: a file with a REAL header line `<!-- evidence-lint: grandfathered until <date> -->`
# is skipped entirely (owner grants via this channel, never self-served; a mention inside a spec
# quote is NOT a grant). `GH_SKIP_RESOLUTION=1` disables the gh api calls (local offline check).
#
# The runner has no rg: grep/sed/awk only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-daniel-castilho/dargent}"

cd "$REPO_DIR"

# Owner adjudication 2026-09-07 (E13 close): the id pattern widens from `33\d+` to
# `3[0-9]{9,}` — GitHub run ids entered the 34… range (34079606961, 34094292279) and the
# literal 33-prefix had a blind spot. Same ±1-line adjacency contract.
ID_RE='`3[0-9]{9,}`'
NUM_RE='(^|[^0-9])#([0-9]+)'

FAILS=()
total_ids=0

# Scan a region of a file (start..end lines, 1-indexed inclusive; end 0 = EOF).
scan_region() {
    local file="$1" start="$2" end="$3"
    local line_count
    line_count=$(wc -l < "$file")

    local I=1
    while IFS= read -r raw || [[ -n "$raw" ]]; do
        if (( I < start )); then I=$((I+1)); continue; fi
        if (( end > 0 && I > end )); then break; fi

        # (b) run-number presence in the ±1 window around this line
        local window_has_num=0
        local J=$((I>1 ? I-1 : 1)); [[ $J -lt $start ]] && J=$start
        local WEND=$((I+1)); [[ $end -gt 0 && $WEND -gt $end ]] && WEND=$end
        while [[ $J -le $WEND ]]; do
            local wline
            wline=$(sed -n "${J}p" "$file")
            if [[ "$wline" =~ $NUM_RE ]]; then window_has_num=1; fi
            J=$((J+1))
        done

        # extract ids on this line
        local s="$raw"
        while [[ "$s" =~ $ID_RE ]]; do
            id=${BASH_REMATCH[0]}
            id=${id#\`}
            id=${id%\`}
            total_ids=$((total_ids + 1))

            if (( ! window_has_num )); then
                FAILS+=("$file:$I: id \`$id\` has no run #N within ±1 line")
            fi

            if [[ -z "${GH_SKIP_RESOLUTION:-}" ]] && \
                    ! gh api -H 'Accept: application/vnd.github+json' \
                    "repos/$REPO/actions/runs/$id" >/dev/null 2>&1; then
                FAILS+=("$file:$I: id \`$id\` does NOT resolve (gh api != 200)")
            fi
            s=${s#*"${BASH_REMATCH[0]}"}
        done
        I=$((I+1))
    done < "$file"
}

scan_file() {
    local file="$1"
    [[ -f "$file" ]] || return 0
    if grep -qE '^\s*<!-- evidence-lint: grandfathered until ' "$file"; then
        echo "SKIP (grandfathered) $file"
        return 0
    fi

    local total=$(wc -l < "$file")
    if [[ "$file" == "docs/epics.md" ]]; then
        scan_region "$file" 1 0
        return 0
    fi

    # Spec files: only the acceptance-matrix section. The heading is usually `## §10 Acceptance matrix`,
    # but a spec may number its matrix differently (e.g. §6) — match on the title, not the number.
    local start=$(grep -nE '^## §[0-9]+ .*Acceptance matrix' "$file" | head -1 | cut -d: -f1)
    if [[ -n "$start" ]]; then
        scan_region "$file" "$start" 0
        return 0
    fi
    # A spec file with no §10 matrix is out of contract scope.
    echo "SKIP (no §10 matrix) $file"
}

for f in docs/epics.md tasks/*/*-spec.md; do
    [[ "$f" == *.md ]] || continue
    scan_file "$f"
done

if (( ${#FAILS[@]} > 0 )); then
    printf 'evidence-lint FAIL (%d id(s) scanned, %d violation(s)):\n' "$total_ids" "${#FAILS[@]}"
    for v in "${FAILS[@]}"; do echo "  $v"; done
    exit 1
fi
echo "evidence-lint OK — $total_ids run id(s) resolved, each with its number within ±1 line"