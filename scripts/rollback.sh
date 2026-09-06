#!/usr/bin/env bash
#
# Dargent rollback (E12 S0). Instant weights-to-previous-color, no rebuild, incident one-liner printed.
# Reads the last deploy intent from last-deploy.txt (written by deploy.sh before the first weight flip).
# Authority: deploy-smoke-e12-spec.md §2.
#
# Usage: scripts/rollback.sh [--compose-file <path>] [--compose-project <name>] [--nginx-runtime <path>]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${DARGENT_COMPOSE_FILE:-$REPO_DIR/docker/compose.yaml}"
NGINX_RUNTIME_CONF="${DARGENT_NGINX_RUNTIME:-$REPO_DIR/deploy/runtime/nginx.conf}"
LAST_DEPLOY_FILE="$REPO_DIR/deploy/runtime/last-deploy.txt"

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

note() { echo "ROLLBACK $(date +%T): $*"; }
die()  { echo "ROLLBACK $(date +%T) ABORT: $*" >&2; exit 1; }

[[ -f "$LAST_DEPLOY_FILE" ]] || die "no last-deploy.txt — nothing to roll back (deploy.sh writes it before cutover)"

# last-deploy.txt lines: previous <color> / current <color> / tag <tag> / at <iso>
PREVIOUS=$(awk '$1=="previous"{print $2}' "$LAST_DEPLOY_FILE")
CURRENT=$(awk '$1=="current"{print $2}' "$LAST_DEPLOY_FILE")
TAG=$(awk '$1=="tag"{print $2}' "$LAST_DEPLOY_FILE")

[[ -n "$PREVIOUS" && -n "$CURRENT" ]] || die "malformed last-deploy.txt (missing previous/current)"

note "rolling $CURRENT -> $PREVIOUS ($TAG), no rebuild"

# Bring the previous color back UP (after a full cutover the old color was drained+stopped) before
# redirecting traffic; starting an already-running container is a no-op.
compose start "$PREVIOUS" >/dev/null || die "could not start $PREVIOUS"

# render runtime conf: previous color at 100, current color down
if [[ "$PREVIOUS" == "api-blue" ]]; then IDLE="api-green"; else IDLE="api-blue"; fi
# In-place edit note: `awk ... > $NGINX_RUNTIME_CONF` truncates the target BEFORE awk reads it, so the
# render lands in a temp file and is copied over — the same path in/out zeroes the file silently.
# The runtime conf is bind-mounted as a DIRECTORY (docker/compose.yaml) so this host write resolves by
# name on reload; a single-file bind would pin the inode and reloads would read stale config (E12 S2).
awk -v active="$PREVIOUS" -v idle="$IDLE" '
    /server api-(blue|green):8080 resolve/ {
        if (!done) { print "    server " active ":8080 resolve weight=100 max_fails=3 fail_timeout=10s;"; done = 1 }
        else        { print "    server " idle   ":8080 resolve down;" }
        next
    }
    { print }
' "$NGINX_RUNTIME_CONF" > "$NGINX_RUNTIME_CONF.__tmp"
cat "$NGINX_RUNTIME_CONF.__tmp" > "$NGINX_RUNTIME_CONF" && rm -f "$NGINX_RUNTIME_CONF.__tmp"
# The runtime conf is bind-mounted as a DIRECTORY (docker/compose.yaml), so this host write resolves by
# name on reload. (A single-file bind would pin the inode and reloads would read stale content — E12 S2.)
[[ -s "$NGINX_RUNTIME_CONF" ]] && grep -q '^events {' "$NGINX_RUNTIME_CONF" \
    || die "rollback rendered an empty/invalid runtime conf"
compose exec -T nginx nginx -t >/dev/null || die "nginx -t failed"
compose exec -T nginx nginx -s reload || die "nginx reload failed"
note "$PREVIOUS back to 100%, $CURRENT down — reload applied, traffic back on $PREVIOUS"

echo "INCIDENT one-liner: rollback ${PREVIOUS}→${CURRENT} reverted to ${PREVIOUS} at $(date -u +%Y-%m-%dT%H:%M:%SZ) (deploy $TAG cut over, panicked)"
note "ROLLBACK OK"