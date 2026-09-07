# E13 Sequence — order, blocks, stop conditions

```
Block 1:  S0 SpotBugs+Spotless → S1 JaCoCo+OWASP → S2 Trivy+SBOM → S3 CodeQL+DepReview  → STOP
Block 2:  S4 riders (R1/R2/R3) → S5 threat model + catalog + E13 ✅ + M4 ✅ + citation   → audit-first
```

Order rationale: the gate spine first (each step independent, each green on its own PR) — riders and
the threat model depend on the gates existing (evidence-lint is the DOD made mechanical; the threat
model cites the gates as controls).

## Step gates

- One gate per step, green on the epic's own chain before the next lands. A gate that cannot go green
  without suppressing findings → the suppressions ARE the deliverable evidence (rationale lines, P4).
- PR flow (E12 precedent): one PR per step or per tight pair; merges verified clean (tree-identity
  check when citation-adjacent).
- DOD §1 in every handoff; counts from surefire/anchored patterns; report state + gaps.

## Stop conditions (P1–P6)

### P1 — Floor/suppression pressure (lower a floor to pass; suppress a finding without reading it)
STOP, report. Floors and gates are owner-fixed contracts. If a floor is unmeetable, the handoff proves
it (module/coverage report pasted) and the owner re-adjudicates — never the job config.

### P2 — NVD/tooling outage handling (silent-pass on tool failure)
STOP, report. Tool down → documented retry/backoff or explicit `continue-on-error` WITH a tracking
comment in the workflow — a gate that can silently not run is not a gate.

### P3 — Scope creep (SAST beyond java, license gates, secret scanning rewrites, SSO)
Out. The design §11 list + the four riders is the whole epic; the rest is post-v1.0.0.

### P4 — Suppression sprawl (exclusions without rationale; spotless reformat touching semantics)
STOP, report. Every exclusion carries a reason; the normalize commit is semantically empty (verified).

### P5 — Docs vs config divergence (standing rule)
STOP with exact lines. The README/design gate lists get their truth pass in S5 with proof pasted.

### P6 — Evidence discipline (standing rule; now also enforced by R1 once it lands)
Zero-from-memory; pairs number AND id; surefire/anchored counts; report state + gaps, never closure.
