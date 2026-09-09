# M5 Block 1 — EXECUTION PROMPT (S0–S1: the abstraction proof)

Milestone epic — full package: `m5-{prompt,backlog,sequence,spec}.md`. Q-batch BEFORE executing
(D1 seam surface + D2 card-sim shape are YOUR first two questions — argue them with tree evidence).
Attribution conflicts ASKED, never chosen. Self-audit attaches at handoff (TD-36 ratified); channel
audits closure (TD-35 checklist). **STOP after S1.**

---

## S0 — Strategy seam extraction (1 PR)

1. Extract the rail seam at the payments module edge — the MINIMUM surface Card needs (your Q-batch
   proposes the exact shape; no speculative generality).
2. PIX becomes the first implementation. **Zero PIX-domain files edited** — the PR carries the
   diff-audit as a text artifact.
3. Proof (the north star): full suite + ALL ladder ITs + property tests + floors green; PIX
   behavior byte-identical (smoke unchanged).
4. Evidence: run pairs + the diff-audit + floors line.

## S1 — Card rail (1 PR)

1. `CardStrategy` behind the S0 seam; psp-simulator card profile (PspProfile precedent) — minimal
   instant-charge auth/decline. The proof is the abstraction, NOT card-network realism.
2. Card money flows through the SAME invariants: journal, ladder, idempotent replay, outbox. No
   weaker parallel path.
3. ITs: card happy (create → card-confirm → CONFIRMED journaled), card decline (→ failed, no journal
   lies), no-double-journal on card replay.
4. Demo overlay line: card runnable locally end-to-end.
5. Evidence: run ids; card state rows in the state audit.

---

**Block 1 DoD**: verbatim `git log` + `gh run list`; the diff-audit; card IT run ids; floors line;
flags + non-closures declared; nothing self-served. **STOP — Block 2 (S2–S5: cache, k6 gate,
reprocess admin, docs+M5 flip+citation) is commissioned only after channel audit.**
