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

### S3 — k6 hard gate (D4) — 1 PR

1. Threshold from the E16 honest numbers: p95 create tripwire (channel leaning: 2× the 37.38 ms
   honest p95 → 75 ms, generous vs the 250 ms SLO) + error-rate tripwire (0-tolerance). Q-batch
   argues final numbers + margin.
2. Cadence + budget (Q-batch): per-push on main only vs nightly + PR-label; VU count sized to CI.
3. Flake policy: one retry on ambient failure; genuine breach = red. **One intentionally-breached
   run shown red in-PR (bite-proof).**
4. Evidence: green run + red run + the gate config.

### S4 — Webhook reprocessing admin — 1 PR

1. Admin endpoint: re-drive a stored `webhook_events` row through the intake path (dedupe =
   idempotent by design; double-process impossible by the existing event_id UNIQUE).
2. House ladder: unset→404-hidden, wrong key→401, right→200 (+body), real actor in audit_log
   (mirrors OutboxAdminRotationIT).
3. Key posture (Q-batch): reuse `DARGENT_OUTBOX_ADMIN_KEY`-style dedicated env
   (`DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY`) vs shared admin key — spec §4 table entry with rationale.
4. Counter for reprocess invocations; observability line.
5. Evidence: rotation IT run ids; negative-path log lines.

### S5 — Docs + milestone flip + citation — 1 PR

1. design.md §M5 unlock: target-state → present; the §1.2 non-goal rows move to delivered.
2. README: money-flow paragraph finally true at "card"; milestone table **M5 ✅ — the plan
   completes**; scenario catalog rows for card.
3. CHANGELOG consolidated; lessons row (the extraction story).
4. Flip (last content) → citation (ONE) → silence. Tag v1.2.0 = owner call post-epic.
5. Evidence: verbatim chain + run list.
