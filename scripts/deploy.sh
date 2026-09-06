#!/usr/bin/env bash
#
# Dargent blue-green deploy (E12 S0). Authority: deploy-smoke-e12-spec.md §2 + design.md §11.2.
# Fail-closed cutover is the contract (P1): ANY failed precondition/step aborts to the OLD color at
# 100%, the new color is drained and stopped, exit is non-zero with the offending step named.
#
# Usage:  scripts/deploy.sh <tag> [--canary 10,30,100] [--key <raw-api-key>] [--since <ref>]
#                            [--compose-file <path>] [--compose-project <name>] [--nginx-runtime <path>]
#         scripts/deploy.sh --init                render the runtime NGINX conf (blue 100 / green down)
#         scripts/deploy.sh --check <tag> [--since <ref>] [--canary ...]
#                                                print plan + migration-gate verdict, touch nothing
#         scripts/deploy.sh --wait-ready <api-blue|api-green> [--compose-file ...] [--compose-project ...]
#         scripts/deploy.sh --active             print the active color from the runtime conf
#
# CANARY default 10,30,100 (design contract). Minimum 30s dwell per step, smoke.sh probe after each
# bump. Weights are rendered into the RUNTIME copy of the NGINX conf (template is never mutated).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
COMPOSE_PROJECT="${DARGENT_COMPOSE_PROJECT:-dargent}"
NGINX_RUNTIME_CONF="${DARGENT_NGINX_RUNTIME:-$REPO_DIR/deploy/runtime/nginx.conf}"
NGINX_TEMPLATE="$REPO_DIR/docker/nginx/nginx.conf"
LAST_DEPLOY_FILE="$REPO_DIR/deploy/runtime/last-deploy.txt"

DWELL_SECONDS=30
# compose healthcheck interval (10s) x 3 — named budget (spec §2).
READY_BUDGET_SECONDS=$((10 * 3))

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

usage() { grep '^#' "$0" | sed -n '2,14p' >&2; exit 1; }

note() { echo "DEPLOY $(date +%T): $*"; }
warn() { echo "DEPLOY $(date +%T) WARN: $*" >&2; }
die()  { echo "DEPLOY $(date +%T) ABORT: $*" >&2; exit 1; }

# --------------------------------------------------------------------------- nginx render
render_runtime_conf() {
    # Regenerate the runtime conf from the template with the two variable lines rendered.
    mkdir -p "$(dirname "$NGINX_RUNTIME_CONF")"
    local active="$1" idle="$2" weight="$3"
    local active_line idle_line
    if [[ "$weight" -ge 100 ]]; then
        active_line="    server $active:8080 resolve weight=100 max_fails=3 fail_timeout=10s;"
        idle_line="    server $idle:8080 resolve down;"
    else
        active_line="    server $active:8080 resolve weight=$weight max_fails=3 fail_timeout=10s;"
        idle_line="    server $idle:8080 resolve weight=$((100 - weight)) max_fails=3 fail_timeout=10s;"
    fi
    awk -v al="$active_line" -v il="$idle_line" '
        /server api-(blue|green):8080 resolve/ {
            if (!done_al) { print al; done_al = 1 } else { print il }
            next
        }
        { print }
    ' "$NGINX_TEMPLATE" > "$NGINX_RUNTIME_CONF"
    [[ -s "$NGINX_RUNTIME_CONF" ]] && grep -q '^events {' "$NGINX_RUNTIME_CONF" \
        || die "render produced an empty/invalid runtime conf"
    note "rendered $NGINX_RUNTIME_CONF (active=$active weight=$weight, idle=$idle)"
}

nginx_reload() {
    # The runtime conf is bind-mounted as a DIRECTORY (docker/compose.yaml), so host writes resolve by
    # name on reload. A single-file bind mount would pin the inode at mount time and reloads would
    # silently read stale config (see docker/nginx binding research, E12 S2) — hence the directory.
    compose exec -T nginx nginx -t >/dev/null || die "nginx -t failed for $NGINX_RUNTIME_CONF"
    compose exec -T nginx nginx -s reload
    note "nginx reloaded"
}

# --------------------------------------------------------------------------- colors
active_color() {
    # active = the upstream in the runtime conf that is NOT `down` (ties → api-blue active)
    local blue_line green_line
    blue_line=$(grep 'server api-blue:' "$NGINX_RUNTIME_CONF" || true)
    green_line=$(grep 'server api-green:' "$NGINX_RUNTIME_CONF" || true)
    if grep -q 'down' <<<"$green_line"; then echo "api-blue"; return 0; fi
    if grep -q 'down' <<<"$blue_line"; then echo "api-green"; return 0; fi
    echo "api-blue"
}

# --------------------------------------------------------------------------- readiness (management port, never :8080)
wait_ready() {
    local color="$1"
    local deadline=$(( $(date +%s) + READY_BUDGET_SECONDS ))
    while (( $(date +%s) < deadline )); do
        local body
        body=$(compose exec -T "$color" wget -qO- http://127.0.0.1:9090/actuator/health 2>/dev/null || true)
        if grep -q '"status":"UP"' <<<"$body"; then
            echo "$color"
            return 0
        fi
        sleep 2
    done
    echo ""
    return 1
}

# --------------------------------------------------------------------------- migration gate (D16)
migration_gate() {
    local since="$1" tag="$2"
    local last="$since"
    local verdict="PASS"
    if [[ -z "$last" ]]; then
        last=$(git -C "$REPO_DIR" describe --tags --abbrev=0 --match 'v*' "$tag" 2>/dev/null || true)
        [[ -n "$last" ]] || { echo "no previous release tag reachable from $tag — gate vacuous"; return 0; }
    fi
    note "migration gate: $last..$tag"
    local range_diff
    range_diff=$(git -C "$REPO_DIR" diff --name-only "$last..$tag" 2>/dev/null || git -C "$REPO_DIR" diff --name-only "$last" "$tag" || true)
    local migration
    migration=$(grep -E 'db/migration/.*\.sql$' <<<"$range_diff" || true)
    [[ -n "$migration" ]] || { echo "no migrations in $last..$tag — gate PASS"; return 0; }
    local file bad=0
    while IFS= read -r file; do
        local content
        content=$(git -C "$REPO_DIR" show "$tag:$file" 2>/dev/null || true)
        [[ -n "$content" ]] || continue
        local line
        while IFS= read -r line; do
            if grep -qE '\bDROP\s' <<<"$line" \
                || grep -qE '\bRENAME\s' <<<"$line" \
                || grep -qE 'ALTER\s+COLUMN.*\bTYPE\b' <<<"$line" \
                || grep -qE '\bSET\s+NOT\s+NULL\b' <<<"$line"; then
                echo "  D16 $file:$line" >&2
                bad=1
            fi
        done <<<"$content"
    done <<<"$migration"
    if [[ "$bad" -eq 1 ]]; then
        verdict="FAIL"
    fi
    if [[ "$verdict" == "FAIL" ]]; then
        echo "migration-gate FAIL: expand-only violations found in $last..$tag (list above)" >&2
        return 1
    fi
    echo "migration-gate PASS — $last..$tag is expand-only"
}

# --------------------------------------------------------------------------- smoke probe against the LIVE stack
smoke_probe() {
    local key="$1"
    "$SCRIPT_DIR/smoke.sh" "${TRAFFIC_BASE:-http://localhost:8080}" "$key" "${PSP_BASE:-http://localhost:8090}"
}

# --------------------------------------------------------------------------- modes: --init / --check / --wait-ready / --active
do_init() {
    [[ -f "$NGINX_TEMPLATE" ]] || die "nginx template missing: $NGINX_TEMPLATE"
    render_runtime_conf api-blue api-green 100
    note "init done — runtime conf at $NGINX_RUNTIME_CONF"
}

do_active() {
    active_color
}

do_wait_ready() {
    local color="$1"
    echo "waiting for $color on :9090/actuator/health (budget ${READY_BUDGET_SECONDS}s)"
    wait_ready "$color" || die "readiness timed out for $color"
    note "$color READY"
}

do_check() {
    local tag="$1" since="" canary="10,30,100"
    local prev="" a
    for a in "$@"; do
        case "$prev" in
            --since) since="$a" ;;
            --canary) canary="$a" ;;
        esac
        prev="$a"
    done
    git -C "$REPO_DIR" rev-parse --verify --end-of-options "${tag}^{commit}" >/dev/null || die "tag $tag does not exist"
    local gate
    gate=$(migration_gate "$since" "$tag" 2>&1) || true
    echo "PLAN deploy $tag (canary $canary)"
    echo "  current template : $NGINX_TEMPLATE"
    echo "  runtime conf     : $NGINX_RUNTIME_CONF"
    echo "  active color     : $(active_color)"
    echo "  migration-gate   : $gate"
    if grep -q 'FAIL' <<<"$gate"; then
        echo "CHECK RESULT: FAIL — deploy would abort (gate) — nothing touched"
        exit 1
    fi
    echo "CHECK RESULT: PASS — deploy plan valid"
}

do_wait_ready_mode() {
    do_wait_ready "$1"
}

# --------------------------------------------------------------------------- main deploy
deploy() {
    local tag="${1:?usage}" canary="10,30,100" key="" since=""
    shift
    while (($#)); do
        case "$1" in
            --canary) canary="$2"; shift 2 ;;
            --key)    key="$2";   shift 2 ;;
            --since)  since="$2"; shift 2 ;;
            *) die "unknown arg $1" ;;
        esac
    done
    [[ -n "$key" ]] || die "--key <raw-api-key> is required (smoke probes after weight bumps)"
    [[ -f "$NGINX_TEMPLATE" ]] || die "nginx template missing: $NGINX_TEMPLATE"
    [[ -f "$NGINX_RUNTIME_CONF" ]] || { note "runtime conf missing — running --init"; do_init; }

    note "precondition 1/3: tag $tag exists"
    git -C "$REPO_DIR" rev-parse --verify --end-of-options "${tag}^{commit}" >/dev/null || die "tag $tag does not exist"

    note "precondition 2/3: migration gate"
    local mg
    if ! mg=$(migration_gate "$since" "$tag" 2>&1); then
        note "$mg"
        die "migration gate FAIL — not cutover (expand-only required)"
    fi
    note "$mg"

    local active idle weight
    active=$(active_color)
    [[ "$active" == "api-blue" || "$active" == "api-green" ]] || die "cannot determine active color from $NGINX_RUNTIME_CONF"
    if [[ "$active" == "api-blue" ]]; then idle="api-green"; else idle="api-blue"; fi
    note "active=$active idle=$idle"

    note "precondition 3/3: start + READY $idle"
    compose up -d "$idle"
    wait_ready "$idle" || abort "$idle" "$active" "readiness TIMEOUT for $idle ($(active_color) stayed active)"
    note "$idle READY on :9090"

    # record deploy intent before first flip (rollback.sh reads this)
    { echo "previous $active"; echo "current $idle"; echo "tag $tag"; echo "at $(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$LAST_DEPLOY_FILE"

    local step_total=0
    IFS=',' read -ra steps <<< "$canary"
    for weight in "${steps[@]}"; do
        step_total=$((step_total + 1))
        note "canary step $step_total: $idle weight=$weight"
        render_runtime_conf "$idle" "$active" "$weight"
        nginx_reload
        note "dwell ${DWELL_SECONDS}s"
        sleep "$DWELL_SECONDS"
        note "post-bump smoke probe"
        if ! smoke_probe "$key"; then
            abort "$idle" "$active" "smoke FAIL at weight=$weight (step $step_total)"
        fi
    done

    note "cutover complete (100% on $idle) — draining old color $active"
    if ! compose stop -t 30 "$active" >/dev/null; then
        warn "drain of $active exited non-zero — new color is 100% and healthy; run rollback.sh if needed"
        exit 1
    fi
    note "DEPLOY OK — $idle active at 100%, old color $active drained and stopped"
}

abort() {
    local idle="$1" active="$2" why="$3"
    echo "DEPLOY $(date +%T) ABORT: $why" >&2
    echo "  restoring $active to 100% and stopping $idle (fail-closed cutover)" >&2
    render_runtime_conf "$active" "$idle" 100
    nginx_reload >&2 2>/dev/null || true
    compose stop -t 30 "$idle" >/dev/null 2>&1 || true
    exit 1
}

# --------------------------------------------------------------------------- dispatch
MODE="${1:-}"
case "$MODE" in
    --init) do_init ;;
    --active) do_active ;;
    --wait-ready) shift; do_wait_ready_mode "${1:?color required}" ;;
    --check) shift; do_check "$@" ;;
    --help|-h) usage ;;
    "")
        usage
        ;;
    *)
        # deploy mode: first arg is the tag
        deploy "$@"
        ;;
esac