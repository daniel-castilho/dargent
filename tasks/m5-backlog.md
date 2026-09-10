# M5 — Backlog (S0–S5)

Q-batch first; 1 PR per step/pair; evidence verbatim; STOP after Block 1. Blocks: B1 = S0+S1 (the
abstraction proof — they land TOGETHER or not at all), B2 = S2–S5.

---

## BLOCK 1

### S0 — Strategy seam extraction (D1) — 1 PR

1. Extract the rail seam at the payments module edge (Q-batch proposes the exact surface; channel
   leaning: `PaymentRail` with initiate / confirm-webhook handling / rail-specific identifiers —
   the minimum that Card needs, nothing speculative).
2. PIX becomes the first implementation. **Zero PIX-domain file edited** (diff-audit listed in PR).
3. Proof: full suite + ALL ladder ITs + property tests + floors green, byte-identical behavior on
   the PIX path (smoke unchanged).
4. Evidence: run pairs, the diff-audit, floors line.

### S1 — Card rail (D2) — 1 PR

1. `CardStrategy` behind the S0 seam; **psp-simulator gains a card profile** (PspProfile precedent):
   minimal auth/decline semantics — instant-charge model mirroring the PIX shape (the proof is the
   abstraction, not card-network realism).
2. Card payments flow through the SAME invariants: journal, ladder, idempotent replay, outbox.
3. ITs: card happy path (create → card-confirm → CONFIRMED journaled), card decline (→ failed
   state, no journal lies), invariant spot-checks (no-double-journal on card replay).
4. Demo overlay line: card runnable end-to-end locally.
5. Evidence: run ids; the two-state machine rows for card in the state audit.

## STOP — BLOCK 1 AUDIT (channel). The abstraction proof is reviewed BEFORE the ops layer builds on it.

---

## BLOCK 2

### S2 — Redis read cache (D3) — 1 PR

1. ONE hot read path (Q-batch: idempotent-replay lookup vs API-key auth lookup, argued with call
   frequency evidence). Redis via Testcontainers IT.
2. Fail-open PROVEN: Redis down → DB fallback, correctness unchanged (IT kills Redis mid-test).
3. TTL + invalidation on revocation (revoked key must never outlive TTL in cache — IT).
4. Hit/miss metrics (`dargent_cache_*`) + one observability.md section.
5. compose: redis service (profile or always-on — Q-batch), connection env per spec §4 table.

### S3 — k6 hard gate (D4) — 1 PR ✅ (opened as PR #49; merged after CI)

1. Threshold from the E16 honest numbers: `create p95 < 75ms` (= 2× the 37.38 ms honest p95,
   generous vs the 250 ms SLO) + `http_req_failed == 0` 0-tolerance; walls replay/pay < 250 ms,
   confirm < 100 ms, checks > 0.99. Bite-proof **in the same job** (G4, `DARGENT_K6_GATE_BITE=1`
   → `p(95)<1ms`, MUST exit red — red is evidence, never retried).
2. Cadence + budget: push to main + `workflow_dispatch` only (no per-PR k6 cost); 8 VUs sized to
   the CI runner (15s ramp → 45s steady).
3. Flake policy: no retry on genuine breach — rename the threshold, don't hope it away. Ambient
   infra failures are the retry case (documented, distinct from breach).
4. Evidence: **local G1-G4 PASS** — G3 green 100% (10262/10262 checks, create p95 28.2 ms,
   0/7330 HTTP failures, 1466/1466 CONFIRMED in-deadline; webhook limiter tuned per E15 spec §4
   so the confirm-leg measures confirm not the abuse limiter) + G4 bite red exit 99. Gate config
   in-PR. PR CI green (job `k6-gate` + full suite).

  *Divergence found during S3 (recorded as FINDING, NOT resolved in-block): the Proving-the-gate-
  locally rule exposed the blue-green upgrade boot break (v1.1.0 volume crash-looped on V113
  below watermark) → fixed by owner-adjudicated Option A (out-of-order migrations), hotfix PR #48
  merge `33daf52`/main CI `34439423891`. S3 PR documents this as the finding that shaped its own
  proving harness.*

### S4 — Webhook reprocessing admin — 1 PR

1. Admin endpoint: re-drive a stored `webhook_events` row through the intake path (dedupe =
   idempotent by design; double-process impossible by the existing event_id UNIQUE).
2. House ladder: unset→404-hidden, wrong key→401, right→200 (+body), real actor in audit_log
   (mirrors OutboxAdminRotationIT).
3. Key posture (Q-batch): **dedicated** `DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY` env (NOT shared with
   outbox) — reprocess can CONFIRM PENDING money, so it gets its own key + own rotation cadence
   (spec §4 table row with rationale).
4. Counter for reprocess invocations; observability line.
5. Evidence: rotation IT run ids; negative-path log lines.

Done ✅ — `reprocess` sealed-outcome re-drive; controller + SecurityConfig explicit rule; audit keyed
to the payment **txid** (not the 51-char provider_event_id — `audit_log.aggregate_id` is varchar(25),
the varchar violation surfaced as a 500 in the ITs and was fixed in place); `IgnoredReProcess` carries
the row's txid; `webhook_reprocessed` audit writes only on a real state change, in the same tx.
Counter `dargent.webhook.reprocess{outcome=…}` (5 frozen tags) — presence asserted in MetricsScrapeIT
(not_found driven over the real surface); WebhookReprocessIT 6/6, WebhookReprocessAdminRotationIT 2/2,
WebhookIntakeUseCaseTest 17/17, full green + k6 gate evidence at the PR.

### S5 — Docs + milestone flip + citation — 1 PR

1. design.md §M5 unlock: target-state → present; the §1.2 non-goal rows move to delivered.
2. README: money-flow paragraph finally true at "card"; milestone table **M5 ✅ — the plan
   completes**; scenario catalog rows for card.
3. CHANGELOG consolidated; lessons row (the extraction story).
4. Flip (last content) → citation (ONE) → silence. Tag v1.2.0 = owner call post-epic.
5. Evidence: verbatim chain + run list.

✅ Done — flip on main (design §1.2 stretch rows delivered, §13 M5 ✅; README money-flow present
tense + card, milestone table **M5 ✅ — MILESTONE TABLE COMPLETE**; playbook card rows 29–31;
CHANGELOG `[1.2.0]` consolidated + S0 story; lessons #21 extraction row; release notes
`docs/releases/v1.2.0.md` pre-authored with the **v1.1.0→v1.2.0 SUPPORTED** migration citation).
Citation (ONE) on `docs/epics.md` E17 = commit B. Silence after.
