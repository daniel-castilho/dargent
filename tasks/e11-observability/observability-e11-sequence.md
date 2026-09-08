# E11 Sequence — order, blocks, stop conditions

```
Block 1:  S0 foundation → S1 JSON-log proofs → S2 on-call drill → S3 lockdown   → STOP, report
Block 2:  S4 metrics → S5 docs truth + E11 flip + citation                       → audit-first
```

Order rationale: see-and-search (logs/correlation/drill/lockdown) before count-and-expose (metrics) —
the maturity-carry rule ("correlação + JSON logs ANTES de métricas"). S0 unblocks everything and is the
only step allowed to touch build files (pom, Dockerfile, compose).

## Step gates

- Every step: tests first where the step is behavior (S1–S4); config steps (S0, S5) land with their
  proof IT in the same commit.
- Commit message = diff. Pairs (number AND id) with every report INCLUDING reds. Zero sleeps,
  injected Clock, barriers at contention, module mains Spring-free (unchanged house rules).
- **DOD (`docs/handoff-dod.md`) is contractual** — run its §2 self-audit before every handoff.

## Stop conditions (P1–P6)

### P1 — Security regression pressure (exposing metrics on the main port, permitAll on prometheus)
STOP, report. The management port isolation IS the security design; "easier" alternatives are owner
decisions. This is the webhook-public E9 §P1 of this epic.

### P2 — Log volume/performance temptation (async appenders, sampling, dropping fields)
STOP, report. v1 logs synchronously to console/json-file (single host, observability.md §2). Async
appenders are an E15/perf decision with measurements.

### P3 — Metric rename/tag pressure
The names/tags in observability.md are FROZEN (documented contract; slos.md cites two of them).
If a name feels wrong mid-flight: STOP, owner decides. Adding an UNDOCUMENTED metric = same rule.

### P4 — Scope creep (Grafana, collector, alerting, dashboards-as-code, tracing)
Out. E11 lands metrics + exposition. Scraping topology/dashboards = E12/E15 (observability.md §5/§7
non-goals stand). DLQ *alerting* is E15; the DLQ *gauge* is in scope.

### P5 — Docs vs config divergence (standing rule)
STOP, report exact lines. S5 flips language only with proof in hand (screenshot-level scrape text in
the handoff, pasted per DOD).

### P6 — Evidence discipline (E9 lessons codified)
Handoffs report state + gaps — never closure claims. Owner sanction quoted, never paraphrased.
Fabricated counts/ids in ANY handoff = formal TD (TD-30 precedent is on the register).

## After Block 1

Owner channel audits (API + tree, per `docs/handoff-dod.md`). On approval, Block 2 prompt is emitted.
E11 flip carries **M4 ◐ preserved** — M4 completes with E12+E13, then E14 closes v1.0.0.
