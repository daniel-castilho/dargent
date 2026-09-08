# E15 Block 1 — EXECUTION PROMPT (S0–S3)

You are executing Block 1 of E15 (Operational Hardening). Full package: `ops-e15-{prompt,backlog,
sequence,spec}.md`. Send your Q-batch BEFORE starting if anything below is ambiguous or conflicts
with an instruction you hold. Attribution conflicts are ASKED, never chosen (DOD §2). On completion:
STOP and hand off — do NOT start Block 2 (S4–S7) without channel audit.

---

## Step S0 — Post-release truth pass (docs+yaml, 1 PR)

1. Land `docker/compose.demo.yaml` (RELAY/LEDGER_CONSUMER/RECONCILER/EXPIRATION `=true`) + the README
   demo line — the E14 S6.5 rider that never landed (TD-35, owner-adjudicated 3/3).
2. README cover: "E14 cuts v1.0.0" → past tense with tag @ `601a669` (paragraph + milestone cell).
3. Mint the E15 row in `docs/epics.md` (☐, milestone `post-1.0.0`; wording per backlog, adjustable
   in Q-batch).
4. Resolve the "104 ids" claim from the E14 citation PR body (verify vs job log/docs; correct or
   annotate) + report current evidence-lint scanned-id count.
5. Evidence: diff --stat docs+yaml-only; evidence-lint green.

## Step S1 — Webhook abuse controls: DEBT-8 REAL closure (code + ITs, 1 PR)

1. In-app rate limit + body cap on `POST /webhooks/psp` — algorithm/limits/values via Q-batch
   (hand-rolled vs bucket4j evidence first; defaults generous; env-tunable per spec §4 table).
2. Carved control order, proven by ITs: oversize → 413 before HMAC consumption; over-limit → 429
   with zero money-path side effects; valid traffic unchanged.
3. New ITs: `WebhookRateLimitIT` (burst → 429 → recovery), `WebhookBodyCapIT` (413); test profile
   raises limits so smoke/demo never trip.
4. Same-PR docs: AGENTS §8 DEBT-8 → RESOLVED (commit+date); threat model surface-1 → mitigated
   in-app, NGINX = defense-in-depth; runbook §4 gains 429/413 glance signals.
5. Evidence: IT run ids; negative-path log lines for 429 and 413; the DEBT-8 row diff.

## Step S2 — Alert rules with a bite (+ optional Grafana rider, 1 PR)

1. Prometheus rule file(s) — minimum set: proof-fail counter, outbox lag vs SLO, DLQ depth,
   signature-failure burst, EXHAUSTED backlog (exact set/names via Q-batch vs real E11 metric
   names). Every rule: severity label + runbook anchor annotation.
2. `promtool test rules`: firing case + quiet case for EVERY rule; CI step runs promtool on every
   push. **Bite-proof: one intentionally-broken rule shown red mid-PR, reverted in-PR.**
3. Rider (her call): Grafana dashboards-as-code in the metrics profile. NO Alertmanager.
4. Evidence: promtool step green run id + the red bite run id (same PR).

## Step S3 — k6 money-path baseline (published number, 1 PR)

1. `scripts/load/k6-money-path.js`: create → idempotent replay → pay → confirm; VU/duration via
   Q-batch; thresholds aligned with SLO buckets.
2. Publish ONE run in `docs/load-test-baseline.md`: p95 per endpoint, throughput, errors, hardware,
   date, commit. Consultative — no CI gate.
3. Evidence: k6 summary verbatim in the doc.

---

## Block 1 DoD (handoff format — precedent E12–E14)

- Verbatim `git log` chain + verbatim `gh run list` pairs; IT run ids; the bite-proof pair;
  k6 summary; DEBT-8 row diff; id-count resolution.
- Flags for channel decision, non-closures declared, nothing self-served.
- **STOP after S3.** Block 2 (S4–S7: scale restore, PITR, DEBT-7 disposition, truth sweep +
  flip + citation) is commissioned only after channel audit.
