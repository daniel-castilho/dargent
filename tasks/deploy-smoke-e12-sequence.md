# E12 Sequence — order, blocks, stop conditions

```
Block 1:  S0 deploy artifacts → S1 drill evidence → S2 runtime-smoke job → S3 shutdown gate  → STOP
Block 2:  S4 riders (N8/N12) → S5 doc riders + E12 flip + citation                            → audit-first
          (S6 optional compose Prometheus — cut freely if it fights the job)
```

Order rationale: the deployable core (scripts + drill + smoke + gate) is the epic's spine and must be
audited as a unit; riders are additive and land after the spine is proven. S0 is the only step touching
build/infra files; S2/S3 own the CI changes.

## Step gates

- Scripts land WITH their drill evidence (S0+S1 may be one commit if the drill is clean) — executable
  documentation or it did not happen.
- Every CI job change lands with the job green on the PR chain (pairs number AND id).
- DOD §1 evidence block in every handoff; TD-30 acknowledgment carries as standing rule (first E11
  handoff satisfied it; honesty standards do not lapse).

## Stop conditions (P1–P6)

### P1 — Cutover safety pressure (skip the migration gate "just this once", force weights while new color is red)
STOP, report. The gate and the fail-closed cutover ARE the epic. A manual override is an owner decision
with owner's hands, never a script flag.

### P2 — Infra scope creep (k8s manifests, Terraform, cloud LB, Ansible)
Out. One host, compose, NGINX with weights — design §11. Anything else is post-v1.0.0.

### P3 — Observability creep (Grafana, dashboards, alert rules, second collector)
Out — E15/trigger-activated. The one profile-gated Prometheus (S6) is the entire allowance; if it grows
config beyond one scrape job, cut it.

### P4 — Flaky smoke protocol
Smoke job red twice without a written cause = STOP (the job's trust is the deliverable). One automatic
rerun allowed per push, labeled in the handoff. Never retry-on-@Disabled-anything.

### P5 — Docs vs config divergence (standing rule)
STOP with exact lines. Release-runbook/README flip language only with proof pasted.

### P6 — Evidence discipline (standing rule)
Zero-from-memory; surefire or anchored `@Test$` counts; report state + gaps, never closure claims.
