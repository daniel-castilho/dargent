# E13 — Execution Prompt, Block 1 (S0–S3)

Engineer brief. Contracts: `quality-security-e13-spec.md` (this package) + design.md §11 +
`docs/handoff-dod.md`. Sequence + stops: `quality-security-e13-sequence.md`. Deliver S0→S3 (one PR per
step or tight pair — E12 precedent), then STOP and report. **The floors (70/75/80/50/40) are
owner-fixed**: bind them from design.md §11's mapping; if the design does not map them explicitly,
STOP and ask — do not guess the binding.

## S0 — SpotBugs + Spotless/Checkstyle

1. Pre-gate normalize commit FIRST (pre-approved): `style: spotless normalize (pre-gate)` — formatting
   only. Handoff proves semantic emptiness (diff review + build green before/after).
2. SpotBugs plugin (parent pom, `max` effort, `Medium` threshold), exclude-filter with per-entry
   rationale lines. Gate on all production modules.
3. Spotless checkstyle format; `spotlessCheck` gate in CI (build job, before verify).

## S1 — JaCoCo floors + OWASP (same step, together — owner directive)

1. JaCoCo aggregate + per-bundle `jacoco-check` in the parent pom. Floors per design §11 mapping
   (STOP if unmapped). **Bite-proof:** on a side branch, temp-lower one floor → CI red → revert →
   paste both runs (the gate must be seen failing once).
2. OWASP Dependency-Check: fail CVSS ≥ 7; `suppressions.xml` (rationale + review date per entry);
   NVD keyless mode with documented retry; tool error MUST fail the job (P2). Expect a slow first run —
   cache the NVD data directory as a CI cache.

## S2 — Trivy 2-pass + SBOM

1. Trivy pass 1: SARIF upload (advisory). Pass 2: `HIGH,CRITICAL` gate — BOTH images (api,
   psp-simulator). Base-image bumps if needed: reuse the runtime-smoke job as the boot proof.
2. SBOM CycloneDX JSON per image → workflow artifact (`sbom-<image>-<tag>.json`). Release attachment
   is E14 — do not wire releases here.

## S3 — CodeQL + Dependency Review

1. CodeQL workflow: java, `security-extended`, push/PR to main.
2. Dependency Review: PR gate, fail on `high`+ new vulnerabilities.

## Handoff (DOD §1 — full block)

`git log --oneline <base>..HEAD` + `git status --porcelain` + `gh run list --limit 8` pasted; for each
gate: the workflow file name + the green run pair (number AND id) + one raw excerpt of the gate's own
output (SpotBugs summary line, OWASP report section, Trivy pass-2 verdict, CodeQL status); the bite-proof
pair for JaCoCo; the normalize commit's semantic-emptiness evidence. Then STOP — Block 2 (riders
R1–R3 + threat model R4 + E13 ✅ + M4 ✅ flip + citation) is commissioned only after this channel's audit.

STOP conditions: P1 floor/suppression pressure · P2 tool-outage silent-pass · P3 scope creep ·
P4 suppression sprawl · P5 docs divergence · P6 evidence discipline.
