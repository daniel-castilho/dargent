# E13 Backlog — Quality & Security Gates

Epic goal: the README/design gate list runs on every push and PR, green — and stays green; the floors
are real numbers that fail CI; the evidence rules are machine-enforced.

```
E13 Quality & Security Gates (M4 — completes it)
├── S0  SpotBugs (max effort, gate) + Spotless/Checkstyle (pre-gated normalize commit)   [Block 1]
├── S1  JaCoCo floors 70/75/80/50/40 + OWASP Dependency-Check (CVSS≥7 gate)              [Block 1]
├── S2  Trivy 2-pass (SARIF advisory + HIGH/CRITICAL image gate) + SBOM CycloneDX        [Block 1]
├── S3  CodeQL (java, security-extended) + Dependency Review (PR gate)                   [Block 1]
├── S4  Riders: R1 evidence-lint · R2 readiness-SQS/SNS · R3 ledger-admin segregation    [Block 2]
├── S5  R4 threat model + matrix/catalog update + E13 ✅ + M4 ✅ flip + citation          [Block 2]
```

### S0 — SpotBugs + Spotless (Block 1)
- SpotBugs `max` effort, `Medium` threshold gate (fail on Medium+), exclusions file committed WITH
  rationale per exclusion (plain-text reason line — suppressions without reasons are P4).
- Spotless with checkstyle: `spotlessCheck` gate after the ONE normalize commit (pre-approved).
- **Accept:** both gates green on the chain; the normalize commit shows zero semantic diff.

### S1 — JaCoCo + OWASP (Block 1, together per owner directive)
- JaCoCo aggregate report per module + bundle check; floors bound per design.md §11 (STOP if unmapped).
  Floors are bundle-level checks in the parent pom — no module exempt unless the design says so.
- OWASP Dependency-Check: fail on CVSS ≥ 7; `suppressions.xml` committed; EVERY suppression entry
  carries a rationale comment + review date. NVD API key via existing secret mechanism if available —
  if the key is absent, the job uses the bundled NVD mirror with documented retry (never silent-pass —
  P4).
- **Accept:** floors intentionally tested once by a temp-lowering commit on a SIDE branch (evidence
  the gate bites — revert after; paste both runs).

### S2 — Trivy + SBOM (Block 1)
- Trivy pass 1: SARIF upload (advisory, non-blocking). Pass 2: image scan `HIGH,CRITICAL` → gate.
  Base-image pinning review: any fix = bump + evidence the image still boots (runtime-smoke job is the
  free proof — reuse it).
- SBOM: CycloneDX JSON per image, uploaded as workflow artifact (attachment to GitHub Releases = E14).

### S3 — CodeQL + Dependency Review (Block 1)
- CodeQL: java, `security-extended`, default query set; analysis on push/PR to main.
- Dependency Review on PRs: fail on severe (`high`+) new vulnerabilities.

### S4 — Riders (Block 2)
- **R1 evidence-lint:** `scripts/evidence-lint.sh` — extract run ids (`\`33\d{8,}\``) and
  `run #N` references from `docs/epics.md` + `tasks/<epic>/*-spec.md` matrices; assert (a) every id resolves
  via `gh api repos/:repo/actions/runs/:id`, (b) every id has its number adjacent in the same row/line,
  (c) counts in rows match `Tests run:` summaries when both are pasted nearby (heuristic check of
  adjacent pairs only — no magic). Job: PR + nightly. Failure output names the file:line.
- **R2 readiness-SQS/SNS:** management health indicators (existing AWS clients): SQS
  `get-queue-attributes` on the configured queues; SNS `get-topic-attributes`; wired into the
  READINESS group (liveness stays process-only — design §9). Dockerfile/compose healthcheck unchanged.
  IT: readiness DOWN when LocalStack endpoint is wrong (inject bad endpoint via property), UP when good.
- **R3 ledger-admin segregation:** `SecurityConfig`: `/v1/ledger/rebuild`, `/v1/ledger/proof`,
  `/v1/ledger/settlements/**` require the admin key (env names an ACTIVE key; validation first —
  E9 Q11 ladder semantics: 404-hidden unset · invalid/revoked 401 · valid≠admin 403 · valid==admin 200;
  audit actor = the presented key's real identity). Ledger admin IT mirrors OutboxAdminRotationIT
  structure. Existing merchant-key tests for these endpoints updated (they now need the admin key —
  the footprint is expected; list files).

### S5 — Threat model + flip (Block 2)
- `docs/security/threat-model.md`: STRIDE × surfaces table (public webhook / API+keys / admin surfaces
  (outbox+ledger) / PSP seam / compose+NGINX edge / CI+supply chain). Each cell: threat, existing
  control (linked IT/doc), status (mitigated|accepted|gap) — gaps become DEBT rows in AGENTS §8 in the
  same commit (with disposition, even "post-v1.0.0 candidate").
- Catalog scenario 28 pointer: satisfied by `ProductionLockdownIT` (E11 S3) — testing-playbook matrix
  gains the link.
- **Flip:** epics E13 ✅ + **M4 ✅** (chain E11+E12+E13 cited) — LAST content commit; exactly ONE
  citation commit; nothing after.
