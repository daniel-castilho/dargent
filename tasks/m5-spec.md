# M5 — Spec: Stretch (the last milestone)

Milestone row: `| M5 — Stretch | Card as second Strategy, k6 as hard gate, Redis read cache,
webhook reprocessing | ☐ |` (README:197; design.md:632/640 — the lock breaks NOW).

## §1 Objective

Prove the day-0 abstraction promise — a second payment rail lands behind one extracted seam with
the PIX domain untouched — and finish the operations arc: cache on one hot read path, a load gate
with teeth (seeded from the honest numbers), and admin re-drive for stuck webhooks.

## §2 Non-goals

Card-network realism (3DS, auth/capture two-phase, interchange semantics) — the simulated card is
instant-charge by design. Real PSP integration. Horizontal scaling / second process (extraction is
a SEAM, not a deployment split). Alertmanager/pager (landed E16 / post-plan). Tracing (trigger).
Mutation testing. Any gate change beyond the k6 addition. Tag v1.2.0 (owner call post-epic).

## §3 Contracts

| Contract | Source | Disposition |
|---|---|---|
| "Card added without touching the PIX domain" | design.md:632 | S0 diff-audit + behavior-identical proof |
| Same money invariants for every rail | E0–E9 ledger/stack | S1 card rides journal/ladder/idempotency — no weaker path |
| Cache = fail-open reads only | channel leaning, D3 | S2 IT proves Redis-down fallback |
| Gate = measured regression tripwire | E16 honest numbers | S3 thresholds from 37.38ms/432rps baselines |
| Admin house pattern (404/401/200 + actor) | E9 Q11 / E15 R3 | S4 rotation IT mirror |
| New env via §4 table | E14+ discipline | below |
| Flip → citation ONE → nothing after | E11–E16 precedent | S5 |

## §4 New configuration surface (each: rationale + safe default; beyond table → STOP)

| Var (proposed) | Purpose | Default | Notes |
|---|---|---|---|
| `DARGENT_CACHE_REDIS_*` (connection/ttl) | S2 cache | off → DB direct (fail-open trivially) | Q-batch finalizes names |
| `DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY` | S4 admin | empty = 404-hidden | house pattern; reuse vs dedicated via Q-batch |
| k6 gate inputs (VUs, duration, thresholds) | S3 CI | sized to runner budget | thresholds from honest baselines |

## §5 Acceptance matrix (fill at S5)

| Step | Deliverable | Evidence | Status |
|---|---|---|---|
| S0 | seam extracted; PIX-unchanged (diff-audit + suite + ladders + floors) | | ☐ |
| S1 | card rail through the SAME invariants | | ☐ |
| S2 | cache on one path; fail-open proven; metrics | | ☐ |
| S3 | k6 gate biting (green + red proofs) | | ☐ |
| S4 | reprocess ladder + audit actor | | ☐ |
| S5 | sweep + **M5 ✅** + flip + citation | | ☐ |

## §6 Evidence policy

Verbatim run ids/shortshas; the S0 diff-audit as a text artifact in the PR; gate bite pair (green+red)
mandatory; fail-open IT transcript; her self-audit attaches at B1 (TD-36 ratified); channel closure
audit per TD-35 checklist (EVERY adjudicated deliverable vs tree). Evidence wins over prose, same-PR fix.

## §7 Post-M5 (channel-side)

1. Channel declares **M5 CLOSED — the milestone table M0–M5 COMPLETE** (the plan, delivered).
2. **Maturity protocol final round**: the new predictions (registered at opening: Perf 4.0, Testes
   4.75, Deployable 4.75, Scalable 3.5, Observable 4.0, Reliable 4.75) face the evidence; external
   baseline #6 for the series.
3. The post-plan era: owner decides what the project BECOMES (real rail? multi-host? portfolio?).
