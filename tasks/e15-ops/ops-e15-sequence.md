# E15 — Execution Sequence

Blocks, STOP gates, PR pairing, commit discipline. Standing rules apply (Q-batch before execution;
attribution conflicts asked, never self-resolved; evidence verbatim; heredoc/lean hygiene).

---

## Block map

| Block | Steps | Theme | Ends with |
|---|---|---|---|
| 1 | S0–S3 | Truth pass + app-level abuse controls + alerting bite + load number | STOP → channel audit |
| 2 | S4–S7 | Scale restore + PITR + DEBT-7 disposition + docs/flip/citation | E15 ✅ declared |

## PR pairing (1 per step/pair)

| PR | Steps | Contents | Expected CI |
|---|---|---|---|
| #14 | S0 | demo overlay + README cover + E15 row mint + id-count resolution | gates (docs+yaml) |
| #15 | S1 | webhook rate limit + body cap + ITs + DEBT-8 RESOLVED | gates + new ITs |
| #16 | S2 | alert rules + promtool tests in CI (+ Grafana rider) | gates + promtool step + bite-proof |
| #17 | S3 | k6 script + published baseline | gates |
| #18 | S4 | scale drill job + record | gates + dispatch run evidence |
| #19 | S5 | PITR harness + drill record | gates (docs+scripts) |
| #20 | S6 | DEBT-7 disposition (either path) | gates + floors-identical proof |
| #21 | S7 | truth sweep + CHANGELOG + flip + citation | gates on flip + citation pushes |

(PR numbers shifted +1 from the original map: the tasks reorg landed as PR #13, merged `99ef945`.)

Sequencing notes:
- S1 before S2 (the 429/413 signals feed an alert rule; rules PR references real metric names).
- S2's promtool step must bite: one intentionally-broken rule lands in a throwaway commit mid-PR,
  shown red, reverted in the same PR (bite-proof pattern from E14 floors).
- S4/S5 are dispatch/local — they can proceed while docs settle; both records land before S7.
- Freeze: from the S7 flip commit, only the citation follows; validation runs post-citation are
  fine, tree changes are not.

## Commit discipline

- Conventional commits (`feat(security):`, `feat(ops):`, `docs(ops):`, `chore(ci):`).
- Drill records and load baselines are docs commits carrying verbatim text evidence.
- Flip = last content commit; citation = exactly ONE after; nothing after the citation.

## Citation contract for E15 (carved now, executed at S7)

`docs/epics.md` E15 row, citation clause:

> citation: flip `<flip-shortsha>` green on run `<flip-run-id>`; controls evidenced
> (DEBT-8 RESOLVED `@<commit>`, rules bite `<run-id>`, restore-scale RTO `<line>`)

## STOP / abort conditions (any → halt + report, no improvisation)

1. Rate-limit design requiring a heavyweight dependency or touching non-webhook routes → STOP,
   Q-batch with evidence.
2. Alert rule impossible against existing metrics (metric name drift from E11) → STOP; do NOT add
   app metric surface silently — Q-batch decides (new metric vs adjusted rule).
3. Scale drill exceeding CI budget → reduce rows honestly and record the reduction; NEVER fake or
   extrapolate a number.
4. PITR needing host features beyond containers → keep local-documented disposition; do not bend
   CI.
5. DEBT-7 consolidation attempt failing floors/guarantee evidence → fall back to Path B
   (won't-fix-by-design) with the failed attempt recorded. No third state.
6. Any gate red at flip → freeze holds until green; no waiver.
7. Any instruction conflict (real or alleged) → ask, quote the source, wait (post-TD-30 rule).
