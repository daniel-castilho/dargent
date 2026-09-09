#!/usr/bin/env bash
#
# Dargent runtime smoke probe (E12 S2 contract — ONE probe, two consumers: deploy.sh after each
# weight bump and the CI runtime-smoke job). Authority: deploy-smoke-e12-spec.md §2/§3 + design.md.
#
# Usage: scripts/smoke.sh <api-base> <api-key> [psp-base]
#   api-base  e.g. http://localhost:8080  (the NGINX front)
#   api-key   raw API key: `Authorization: Bearer <key>` (Q2: job-inserted psp_test_… key)
#   psp-base  defaults to http://localhost:8090 (the PSP simulator front)
#
# Legs (exit non-zero NAMING the failed leg):
#   1 create    POST /v1/payments → 201 + txid + X-Request-Id
#   2 replay    same key+body → 201, Idempotent-Replay: true, response byte-equal (BD-6)
#   3 pay       POST {psp}/cobs/{txid}/payments → 200 (simulator marks the charge paid)
#   4 confirm   deadline-poll GET /v1/payments/{txid} → CONFIRMED, txid/amount echoed, X-Request-Id
#   5 card      card create → 201 PENDING + rail=card + X-Request-Id (webhook fires during create)
#   6 card      deadline-poll GET → CONFIRMED (same webhook path as PIX; no explicit pay)
#   7 card      GET detail → amount echoed + explicit-null brcode retained (FINDING-S1-2)
#
# Deviation (disclosed in E12 handoff): design §6.2 GET has NO `fee` field; the spec's
# "status/fee/request_id" is a wording artifact — we assert status + txid echo + amount echo + header.
#
set -euo pipefail

API_BASE="${1:?usage: smoke.sh <api-base> <api-key> [psp-base]}"
API_KEY="${2:?usage: smoke.sh <api-base> <api-key> [psp-base]}"
PSP_BASE="${3:-http://localhost:8090}"

curl() { command curl -sS -H "Authorization: Bearer ${API_KEY}" "$@"; }

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
note() { echo "SMOKE $(date +%T): $*"; }

extract_txid() { grep -o '"txid":"[^"]*"' <<<"$1" | head -1 | cut -d'"' -f4; }
extract_http() { grep -o '"status":"[^"]*"' <<<"$1" | head -1 | cut -d'"' -f4; }

KEY="smoke-$(date +%s%N)"
BODY='{"amount":100,"description":"deploy smoke"}'
HDRS=(-H "Idempotency-Key: ${KEY}" -H "Content-Type: application/json")

note "leg 1/4 create"
CREATE=$(curl -w $'\n%{http_code}' -D /tmp/smoke.create.hdr "${HDRS[@]}" -d "$BODY" "$API_BASE/v1/payments")
CREATE_HTTP=${CREATE##*$'\n'}
CREATE_BODY=${CREATE%$'\n'*}
[[ "$CREATE_HTTP" == "201" ]] || fail "leg 1: create expected 201, got $CREATE_HTTP"
TXID=$(extract_txid "$CREATE_BODY")
[[ -n "$TXID" ]] || fail "leg 1: no txid in create response: $CREATE_BODY"
grep -q '"status":"PENDING"' <<<"$CREATE_BODY" || fail "leg 1: not PENDING: $CREATE_BODY"
grep -qi '^x-request-id:' /tmp/smoke.create.hdr || fail "leg 1: X-Request-Id header absent on create"
note "leg 1 ok — txid=$TXID"

note "leg 2/4 idempotent replay"
REPLAY=$(curl -w $'\n%{http_code}' -D /tmp/smoke.replay.hdr "${HDRS[@]}" -d "$BODY" "$API_BASE/v1/payments")
REPLAY_HTTP=${REPLAY##*$'\n'}
REPLAY_BODY=${REPLAY%$'\n'*}
[[ "$REPLAY_HTTP" == "201" ]] || fail "leg 2: replay expected 201, got $REPLAY_HTTP"
grep -qi '^Idempotent-Replay: true' /tmp/smoke.replay.hdr || fail "leg 2: Idempotent-Replay header missing/false"
# BD-6 byte-equal snapshot: the replay body must equal the create body byte-for-byte EXCEPT the
# `expiresIn` token, which is deliberately re-derived live ("remaining time") — it cannot be
# byte-equal without going stale (disclosed in the E12 handoff as a spec-wording artifact, like `fee`).
mask_expires_in() { sed -E 's/"expiresIn":"[^"]*"//' <<<"$1"; }
cmp -s <(mask_expires_in "$CREATE_BODY") <(mask_expires_in "$REPLAY_BODY") || fail "leg 2: replay body differs beyond expiresIn (BD-6): $(diff <(mask_expires_in "$CREATE_BODY") <(mask_expires_in "$REPLAY_BODY") | head -3)"
note "leg 2 ok — byte-equal (modulo live expiresIn)"

note "leg 3/4 pay at simulator"
PAY_HTTP=$(command curl -sS -o /tmp/smoke.pay.body -w '%{http_code}' -X POST "$PSP_BASE/cobs/$TXID/payments")
[[ "$PAY_HTTP" == "200" ]] || fail "leg 3: simulator pay expected 200, got $PAY_HTTP"
note "leg 3 ok"

note "leg 4/4 confirm (deadline poll)"
DEADLINE=$(( $(date +%s) + 90 ))
FINAL=""
while (( $(date +%s) < DEADLINE )); do
    GET_BODY=$(command curl -sS -H "Authorization: Bearer ${API_KEY}" "$API_BASE/v1/payments/$TXID" 2>/dev/null || true)
    if grep -q '"status":"CONFIRMED"' <<<"$GET_BODY"; then FINAL="$GET_BODY"; break; fi
    sleep 1
done
[[ -n "$FINAL" ]] || fail "leg 4: not CONFIRMED within 90s (last body: $GET_BODY)"
[[ "$(extract_txid "$FINAL")" == "$TXID" ]] || fail "leg 4: txid mismatch: $FINAL"
grep -q '"amount":100' <<<"$FINAL" || fail "leg 4: amount not echoed: $FINAL"
GET_HDR=$(command curl -sSI -H "Authorization: Bearer ${API_KEY}" "$API_BASE/v1/payments/$TXID" | grep -i '^x-request-id:' | head -1 | tr -d '\r')
[[ -n "$GET_HDR" ]] || fail "leg 4: X-Request-Id header absent on GET detail"
note "leg 4 ok — CONFIRMED ($(echo "$GET_HDR" | cut -d' ' -f2))"

# ------------------------------------------------------------------- card rail
# Legs 5-7 (M5 S1): the card PSP fires the webhook synchronously during the approve
# create, so there is no explicit pay step — confirm is a poll (same as PIX leg 4).

CARD_KEY="smoke-card-$(date +%s%N)"
CARD_BODY='{"amount":100,"description":"deploy smoke card","method":"card","cardToken":"smoke-card-tok"}'
CARD_HDRS=(-H "Idempotency-Key: ${CARD_KEY}" -H "Content-Type: application/json")

note "leg 5/7 card create"
CARD_CREATE=$(curl -w $'\n%{http_code}' -D /tmp/smoke.card.create.hdr "${CARD_HDRS[@]}" -d "$CARD_BODY" "$API_BASE/v1/payments")
CARD_HTTP=${CARD_CREATE##*$'\n'}
CARD_BODY_RESP=${CARD_CREATE%$'\n'*}
[[ "$CARD_HTTP" == "201" ]] || fail "leg 5: card create expected 201, got $CARD_HTTP"
CARD_TXID=$(extract_txid "$CARD_BODY_RESP")
[[ -n "$CARD_TXID" ]] || fail "leg 5: no txid in card create response: $CARD_BODY_RESP"
grep -q '"status":"PENDING"' <<<"$CARD_BODY_RESP" || fail "leg 5: not PENDING: $CARD_BODY_RESP"
grep -qi '^x-request-id:' /tmp/smoke.card.create.hdr || fail "leg 5: X-Request-Id header absent on card create"
grep -q '"brcode":null' <<<"$CARD_BODY_RESP" || fail "leg 5: brcode not null for card: $CARD_BODY_RESP"
note "leg 5 ok — txid=$CARD_TXID"

note "leg 6/7 card confirm (deadline poll)"
CARD_DEADLINE=$(( $(date +%s) + 90 ))
CARD_FINAL=""
while (( $(date +%s) < CARD_DEADLINE )); do
    G=$(command curl -sS -H "Authorization: Bearer ${API_KEY}" "$API_BASE/v1/payments/$CARD_TXID" 2>/dev/null || true)
    if grep -q '"status":"CONFIRMED"' <<<"$G"; then CARD_FINAL="$G"; break; fi
    sleep 1
done
[[ -n "$CARD_FINAL" ]] || fail "leg 6: card not CONFIRMED within 90s (last body: $G)"
[[ "$(extract_txid "$CARD_FINAL")" == "$CARD_TXID" ]] || fail "leg 6: txid mismatch: $CARD_FINAL"
CARD_GET_HDR=$(command curl -sSI -H "Authorization: Bearer ${API_KEY}" "$API_BASE/v1/payments/$CARD_TXID" | grep -i '^x-request-id:' | head -1 | tr -d '\r')
[[ -n "$CARD_GET_HDR" ]] || fail "leg 6: X-Request-Id header absent on card GET"
note "leg 6 ok — CONFIRMED ($(echo "$CARD_GET_HDR" | cut -d' ' -f2))"

note "leg 7/7 card GET detail echoes amount and keeps explicit-null brcode"
grep -q '"amount":100' <<<"$CARD_FINAL" || fail "leg 7: amount not echoed: $CARD_FINAL"
# The confirmed card detail retains the explicit-null brcode (FINDING-S1-2) and —
# per the E12 deviation above — the GET contract has NO `fee` field, so none is asserted.
grep -q '"brcode":null' <<<"$CARD_FINAL" || fail "leg 7: brcode not null on card GET: $CARD_FINAL"
note "leg 7 ok"

note "SMOKE PASS"