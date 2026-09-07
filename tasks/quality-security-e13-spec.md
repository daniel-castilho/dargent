# E13 Spec — Quality & Security Gates (exact contracts)

Authority: design.md §11 > README M4 lines > owner adjudications (2026-09-06) > this file. Where the
design and this file disagree on a NUMBER (floors), the design wins; where unmapped, STOP and ask.

## §1 Scope

CI/CD gates: SpotBugs, Spotless/Checkstyle, JaCoCo floors, OWASP Dependency-Check, Trivy 2-pass, SBOM,
CodeQL, Dependency Review; riders R1–R4; threat model; flips. Out of scope: mutation testing,
license gates, secret-scanning rewrites, release attachment of SBOM (E14), k6 (E15).

## §2 Gate contracts (Block 1)

- **SpotBugs:** effort `max`, threshold `Medium` (fail Medium+); exclude-filter committed with per-entry
  rationale; runs on all production modules.
- **Spotless/Checkstyle:** `spotlessCheck` gate; ONE pre-gate normalize commit (semantically empty —
  handoff pastes `git diff --stat` of a semantic-sanity check: zero .java behavior files touched beyond
  formatting evidence).
- **JaCoCo:** aggregate + per-bundle check; floors **70/75/80/50/40** bound per design.md §11 mapping
  (STOP if the design lacks the mapping). Floors live in the parent pom; removing/lowering one = P1.
- **OWASP Dependency-Check:** fail CVSS ≥ 7; `suppressions.xml` with rationale + review-date per entry;
  NVD-keyless mode must still FAIL on tool error (P2) — retry documented in the workflow.
- **Trivy:** pass 1 SARIF (advisory upload); pass 2 `HIGH,CRITICAL` gate on the api image (and
  psp-simulator image — same job, both images).
- **SBOM:** CycloneDX JSON per image as workflow artifact (naming: `sbom-<image>-<tag>.json`).
- **CodeQL:** java, security-extended, on push/PR to main. **Dependency Review:** PR gate,
  fail on `high`+ severity additions.

## §3 Rider contracts (Block 2)

- **R1 evidence-lint:** `scripts/evidence-lint.sh` + job. Extracts: run ids matching backtick-quoted
  `33\d+` and `run #\d+` from `docs/epics.md` and `tasks/*-spec.md` §10 matrices. Asserts per file:
  every id resolves (`gh api repos/{repo}/actions/runs/{id}` → 200) and has `run #N` adjacent
  (±1 line). Output: `file:line: violation`. New rows failing = job red. Existing rows grandfathered
  ONLY if the file has a `<!-- evidence-lint: grandfathered until <date> -->` header (owner grants
  via this channel, never self-served).
- **R2 readiness:** new health indicators (management health group `readiness`): SQS
  `get-queue-attributes` on `DARGENT_LEDGER_QUEUE_URL` + `DARGENT_NOTIFS_QUEUE_URL`; SNS
  `get-topic-attributes` on `DARGENT_EVENTS_TOPIC_ARN`. Existing clients only; LocalStack-compatible.
  Liveness untouched. IT: good endpoint → UP; deliberately-bad endpoint (property override) → readiness
  DOWN, liveness UP. Deploy gate (E12 `deploy.sh`) inherits automatically — that is the point.
- **R3 ledger-admin:** new env **`DARGENT_LEDGER_ADMIN_KEY`** (default EMPTY = the three endpoints
  404-hidden). Ladder (E9 Q11 semantics): unset → 404-hidden · no/unknown/revoked key → 401 · valid
  active key ≠ env → 403 · valid == env → 200 (audit `ledger_admin_*` with the presented key's real
  identity; never sentinel). Endpoints: `/v1/ledger/rebuild`, `/v1/ledger/proof`,
  `/v1/ledger/settlements/**`. IT mirrors `OutboxAdminRotationIT` (both contexts). Footprint: existing
  endpoint tests gain the admin key — list every touched file.
- **R4 threat model:** structure above; gaps → AGENTS §8 rows same commit. The document cites CONTROLS
  (IT names, config keys), not intentions.

## §4.1 Environment contract (new names — complete list; defaults are contract)

| Env | Default | Meaning |
|---|---|---|
| `DARGENT_LEDGER_ADMIN_KEY` | empty (404-hidden) | Names the ACTIVE key allowed on ledger admin endpoints (rebuild/proof/settlements) |

Exactly one new env. CI/tool secrets (NVD key etc.) ride repository secrets, not app env.

## §6 Acceptance matrix (skeleton — executor fills with pairs)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | SpotBugs + Spotless gates | `./mvnw verify` (spotless validate + spotbugs max/Medium); normalize f80663f (229 .java, 5134+/3479-); exclusions with rationale | 34079606961 green | ✅ |
| S1 | JaCoCo floors + OWASP | coverage poms + check-coverage.sh; measured 0.864/0.867/1.000/0.888/0.785; bite-proof floor 0.99 red; OWASP CVSS≥7; tomcat 11.0.24 red (9 CVEs) → 11.0.25 green | 34079606961 green | ✅ |
| S2 | Trivy 2-pass + SBOM | SARIF pass 1 + HIGH/CRITICAL pass 2 (both images) + sbom artifacts; bit libcrypto3 3.5.7-r0 CVE-2026-14456 → apk upgrade 3.5.8-r0 (Dockerfile c8891fc) | 34079606961 green | ✅ |
| S3 | CodeQL + Dep Review | codeql.yml push/PR main; dependency-review fail high; dep-graph enabled 2026-09-07 | 34079606961 green | ✅ |
| S4 | R1/R2/R3 | lint job + readiness IT + admin ladder IT | PR #4 run #181 `34094292279` (evidence-lint green 85 ids; `ReadinessHealthGoodIT`/`ReadinessHealthBadIT` UP/DOWN legs; `LedgerAdminHiddenIT` 404-hidden + `LedgerAdminRotationIT` 3/3 ladder; footprint ITs green) | ✅ |
| S5 | threat model + E13 ✅ + **M4 ✅** + citation | docs diff (`docs/security/threat-model.md`, `docs/ci-vulnerability-gates.md`, AGENTS §8 DEBT-7/DEBT-8, design §11.1, README truth pass, playbook scenario 28) | pair | ✅ |
