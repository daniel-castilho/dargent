# E16 Block 1 — EXECUTION PROMPT (S0–S3)

Compact epic — full package: `ops-e16-{prompt,backlog,sequence,spec}.md`. Q-batch BEFORE executing;
attribution conflicts ASKED, never chosen. STOP after S3; hand off with your self-audit record as an
ATTACHMENT (channel audits the block boundary until the owner rules on TD-36 — prompt §1.7).

**S0 — v1.1.0** (position stated in the owner's answer traveling with this package): if TODAY —
docs commit (CHANGELOG `[1.1.0]` + README L196 past-tense fix) → annotated tag → release run green →
verify `:1.1.0` image + jar + SBOM + digest-in-body; if END — skip to S6 tail.

**S1 — Alertmanager**: service in `metrics` profile; config with severity routes → webhook-logger
stub receiver (NO pager, NO external sink); Prometheus `alerting:` wired; `amtool check-config` CI
step (addition-only beside promtool); observability.md section. Evidence: amtool run id + stub
log/alerts-query line.

**S2 — PITR v2**: WAL archive on a SEPARATE container/volume (E15 caveat answered); base backup →
kill → replay to target; measured RPO v2 + honest comparison vs ≈6s same-disk; Q-batch: CI-ify
(dispatch) and/or release.yml wiring (budget evidence decides). Evidence: `docs/drills/pitr-v2-<date>.md`
transcript.

**S3 — k6 honest run**: same script; demo overlay ON (spine real) + DEFAULT limiter (100/0.5);
published BESIDE 414 rps as a two-row comparison; hardware + commit disclosed; note that this seeds
the M5 gate threshold (D4) — recorded, not gated. Evidence: k6 summary verbatim + table.

---

**Block 1 DoD**: verbatim `git log` + `gh run list`; amtool/pitR/k6 evidence; flags + non-closures
declared; nothing self-served. **STOP — do NOT start Block 2 (S4–S6) without channel audit.**
