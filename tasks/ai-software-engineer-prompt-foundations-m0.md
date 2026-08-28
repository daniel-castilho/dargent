# AI Software Engineer Prompt — Foundations & Skeleton M0

## Epic E0 — Project Skeleton, Boundary Gates, CI & Baseline Topology

**Status:** Ready for closure execution — the epic is ~85% as-built; your job is verify, finish and close with evidence
**Priority:** P0 — every other epic (E1..E15) builds on this foundation
**Target:** CI green on the real repository, acceptance matrix fully evidenced, M0 formally closed
**Package:** `io.dargent` plus repository-level configuration, tests, scripts and documentation

You are the Software Engineer owning the completion of the **Foundations & Skeleton (E0 / M0)** epic for the
Dargent API. Most of this epic already exists in the tree. Correct completion takes priority over new work:
you verify what is built, finish what is pending, prove the gates work, and close the milestone with evidence.
You do **not** start M1 work under any circumstance.

---

## Sources of truth — read in this order

1. `AGENTS.md`
2. `pom.xml` and `.github/workflows/ci.yml`
3. `docs/design.md` (§3 architecture, §5 data model conventions, §11 CI, §13 roadmap)
4. `docs/coding-standards.md`
5. `docs/testing-playbook.md` (§2 taxonomy, §4 catalog)
6. `docs/data-model-decisions.md` and `docs/lessons.md` (lesson #11 before touching boundary scripts)
7. `tasks/foundations-m0-spec.md`
8. `tasks/foundations-m0-backlog.md`
9. `tasks/foundations-m0-implementation-sequence.md` — **your execution script**
10. `tasks/m0-acceptance-matrix.md` — the closure artifact you fill
11. Current production code and colocated `*Test` / `*IT` classes

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the same
change set. Do not rely on analysis files that are not tracked in the repository.

---

## Goal

Close M0 with a green pipeline and a fully evidenced acceptance matrix. The epic closes these current risks:

- the boundary gate (`scripts/check-boundaries.sh`) scanned test sources and flagged its own proof fixture —
  the prod-only fix exists locally and must land (lessons.md #11);
- the first real CI runs have not completed green yet — the JDK 25 compile of this tree has never been proven;
- `uploads/image-1.png` (1.1 MB of conversation debris), an outdated `.gitignore` and a missing `LICENSE`
  file are still on `main`;
- the acceptance matrix has `pending` in every evidence column.

---

## Locked technical decisions

These were decided in the design debates and ADRs. Re-litigating them in this epic is out of scope.

1. **Java 25 (LTS), Spring Boot 4.1.x, Maven multi-module** — module split by bounded context, never by layer.
2. **PostgreSQL 16** as source of truth; **Testcontainers 2.0.1** via BOM (Boot 4 does not manage TC anymore;
   module names carry the `testcontainers-` prefix; container class lives in `org.testcontainers.postgresql`).
3. **Maven wrapper is NOT committed** (deviation declared in the acceptance matrix): CI and instructions use
   `mvn`; a developer machine may generate the wrapper later without a spec change.
4. **The boundary gate scans production sources only** (`*/src/main/java/*`). Test-scope violations are
   governed by ArchUnit semantically; the deliberate `BadDomainFixture` under payments tests **must remain**
   — it is the gate-proof required by the M0 acceptance matrix.
5. **Flyway gap-versioned per-module locations**: payments V1xx, ledger V2xx, notifications V3xx; schema-only
   migrations in M0; zero cross-schema FK/JOIN.
6. **Quality/security gates beyond build+image (SpotBugs, OWASP, JaCoCo, Trivy, SBOM, CodeQL, runtime smoke,
   k6) belong to E13/M4** — do not add them now.
7. **LocalStack is stateless and disposable**; no persistence configuration for it in compose.

---

## Non-negotiable engineering rules

1. Work in small, reviewable, conventional commits (`fix(ci): …`, `chore(repo): …`); never deliver the epic
   as one unreviewable change.
2. Read the referenced story acceptance before coding; add tests with the production change, not at the end.
3. Never add a dependency without explicit approval — the dependency set of M0 is locked in the spec §4.
4. A red baseline stops work: triage to root cause before any new change on top.
5. After each step, update story checkboxes in the backlog and note deviations in the sequence file;
   do not silently alter the specification.
6. Every CI behavior change updates the workflow, the local verification path and the acceptance matrix in
   the same change set.
7. Sources are 100% English. Secrets never enter the tree (AGENTS.md §4.2).

---

## Required artifacts (the M0 contract)

- **Reactor:** root `pom.xml` + `modules/{shared,payments,ledger,notifications}` + `apps/{api,psp-simulator}`;
  `mvn -B validate` clean on JDK 25.
- **Gates:** ArchUnit rules per module (domain purity, no sibling imports) + `boundary_gate_rejects_a_deliberate_domain_violation`
  proof + `scripts/check-boundaries.sh` green locally and in CI (production scope).
- **CI:** `build` job (boundary check → `mvn -B verify` → artifacts on failure) + `image` job (API and
  simulator images → non-root gate failing on `uid=0`).
- **Persistence:** `MigrationIT` proves Flyway creates exactly the three module schemas against real PostgreSQL 16.
- **Runtime:** `docker/compose.yaml` + `docker/nginx/nginx.conf` + `docker/.env.example` (blue/green topology).
- **Governance:** LICENSE (MIT), `.gitignore` covering `uploads/` + `internal-notes/`, CHANGELOG, AGENTS.md, docs set.

## Scope exclusions

- No `Payment` entity beyond the `PaymentStatus` seed enum; no business tables; no repositories.
- No REST endpoints beyond Boot defaults (actuator health/info); no security filter chain yet (E3/E4).
- No messaging code (SNS/SQS adapters are E6); no queue provisioning.
- No deploy scripts (`deploy.sh`/`rollback.sh` are E12); compose only.
- No NGINX canary flip logic; the M0 nginx.conf is a static round-robin over blue/green.

## Definition of Done (epic)

### Build & boundaries
- [ ] `mvn -B validate` clean; `bash scripts/check-boundaries.sh` green locally **and** in CI
- [ ] All ArchUnit tests green including the deliberate-violation proof
- [ ] No framework imports in any `src/main/java/**/domain/` path

### CI on the real repository
- [ ] `build` job green end to end (first real JDK 25 compile of the tree)
- [ ] `image` job green: both images build, non-root gate passes
- [ ] A green run link recorded as evidence in the acceptance matrix

### Persistence & topology
- [ ] `MigrationIT` green (three module schemas, business-table absence asserted)
- [ ] Compose config validated (`docker compose config -q`) and, when a Docker host is available,
      healthchecks green for postgres/localstack/nginx/simulator (operator verification, evidence noted)

### Repo hygiene & closure
- [ ] `uploads/` removed from the repository; `.gitignore` current; LICENSE present
- [ ] Every acceptance-matrix row carries a real evidence link or command output reference
- [ ] README "Current state" updated; CHANGELOG Unreleased section reflects the closure; lessons recorded
