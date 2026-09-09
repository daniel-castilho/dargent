# M5 — Execution Sequence

## Blocks

| Block | Steps | Ends with |
|---|---|---|
| 1 | S0 seam extraction + S1 card rail (the abstraction proof — ONE reviewable unit in 2 PRs) | STOP → channel audit |
| 2 | S2 cache · S3 k6 gate · S4 reprocess admin · S5 docs + M5 flip + citation | milestone table COMPLETE |

## PR pairing

| PR | Step | Contents |
|---|---|---|
| #A | S0 | seam extraction, PIX-unchanged proof |
| #B | S1 | CardStrategy + simulator card profile + card ITs |
| #C | S2 | Redis cache + fail-open IT + metrics |
| #D | S3 | k6 gate + bite-proof |
| #E | S4 | reprocess admin + rotation IT |
| #F | S5 | docs + M5 flip + citation |

Sequencing: S1 depends on S0 (same block, reviewed together at STOP). S2/S3/S4 are independent —
any order after the audit; S5 last. Freeze from flip; citation ONE after; nothing later.

## Commit discipline

Conventional (`refactor(payments):`, `feat(card):`, `feat(perf):`, `feat(ops):`, `docs(m5):`).
Flip = last content commit; citation = exactly ONE after; nothing after the citation. Her self-audit
attaches at the B1 handoff (TD-36 ratified); channel closure audit runs the TD-35 checklist.

## Citation contract (carved now)

`docs/epics.md` M5/E16-class row, citation clause:

> citation: flip `<flip-shortsha>` green on run `<flip-run-id>`; abstraction proof
> `<diff-audit-ref>`; card ITs `<run-id>`; gate bite `<run-id>`

## STOP / abort conditions

1. S0 extraction needing to edit PIX domain LOGIC (not just wiring) → STOP + Q-batch (design
   escalation — the seam shape may be wrong).
2. Card semantics creeping toward network realism (3DS, capture models) → STOP; the proof is the
   abstraction.
3. Cache proposing to sit on the money path (writes, journal, ladder) → STOP (fail-open reads only).
4. k6 gate flaking in CI → reduce ambition (nightly) honestly; never a known-flaky gate.
5. Reprocess endpoint able to bypass dedupe → STOP (that's a money-safety defect, not a feature).
6. Instruction conflict → ask, quote, wait (post-TD-30; TD-36 ratified for self-audits).
