# Foundations & Skeleton M0 — Backlog

## Epic E0 — Project Skeleton, Boundary Gates, CI & Baseline Topology

**Priority:** P0
**All stories:** Must
**Companions:** `foundations-m0-spec.md` · `foundations-m0-implementation-sequence.md` · `ai-software-engineer-prompt-foundations-m0.md`

**Execution status:** implemented 2026-08-28 (workspace-built, reactor validated on Maven 3.9.11 against
Boot 4.1.1); closure execution starts from `main` at the commit following
`chore(repo): add CI workflow and MIT license; remove stray files; fix missing import`.
Story checkboxes below are updated per phase. ✅ as-built (verify only) · ◐ partial · ☐ open.

---

## Epic outcome

The repository compiles on JDK 25 through a green CI pipeline, the module boundaries are enforced by two
independent nets (ArchUnit semantics + import/FQN script in CI) with a proof that the gate actually fires,
Flyway owns one schema per module from day one, the compose topology mirrors the target blue-green runtime,
and the milestone closes with a fully evidenced acceptance matrix — the foundation every later epic trusts.

---

## Story map

```text
BASELINE
S0   Environment lock and as-built baseline verification

FOUNDATIONS
S1   Maven multi-module skeleton (reactor, parent, BOMs, dependency set)
S2   Module boundary gates (ArchUnit + gate-proof fixture + prod-only boundary script)

INFRASTRUCTURE
S3   Flyway per-module migrations and schema proof IT
S4   Compose runtime topology and environment contract
S5   Non-root container images

DELIVERY
S6   CI workflow (build gate + image gate) green on the real repository
S7   Repository hygiene (uploads, .gitignore, LICENSE, conventional commits)

GOVERNANCE & CLOSURE
S8   Foundation documents set (AGENTS.md, docs/*, CHANGELOG)
S9   Acceptance matrix evidence and M0 formal closure
```

---

## S0 — Environment lock and as-built baseline verification ✅/☐

### Work
- [x] Confirm toolchain expectations: JDK 25 target (CI `setup-java` temurin 25), Maven 3.9.x, Docker Compose
- [x] Reactor parses: `mvn -B validate` clean (validated with Maven 3.9.11 in the build workspace)
- [ ] Re-verify on current `main` after the hygiene push: `mvn -B validate` + `bash scripts/check-boundaries.sh`
- [ ] Record the closing commit SHA in the acceptance matrix

### Acceptance
- [ ] Both commands exit 0 on the closure commit
- [ ] Any mismatch between docs and tree is either fixed in this epic or declared as a deviation with an owner

## S1 — Maven multi-module skeleton ✅

### Work
- [x] Root `pom.xml` on `spring-boot-starter-parent` 4.1.1; six modules registered; `finalName` pinned for apps
- [x] Testcontainers 2.0.1 BOM imported in `dependencyManagement` (Boot 4 does not manage TC)
- [x] Module split by bounded context; `shared` with zero compile-time framework dependencies
- [x] Failsafe bound to `verify` with `**/*IT` includes and `failIfNoTests=false`
- [x] Seed domain kept minimal: `Money`, `EventEnvelope`, `PaymentStatus`, `EntryDirection` (no M1 scope creep)

### Acceptance
- [x] `mvn -B validate` clean (evidence: build workspace run)
- [ ] `mvn -B verify` green in CI (first real JDK 25 compile) — evidence pending
- [ ] Dependency set matches spec §4 exactly (no unapproved additions)

## S2 — Module boundary gates ◐

### Work
- [x] ArchUnit rules per module: domain purity (no Spring/JPA/Jackson/AWS in `..domain..`), no sibling-module imports
- [x] `boundary_gate_rejects_a_deliberate_domain_violation` proof test with `BadDomainFixture` (Spring
      annotation inside a test-only fake domain package) — the gate must demonstrably fire
- [x] `scripts/check-boundaries.sh`: cross-module import checks + domain purity grep
- [ ] **Prod-only scan fix lands on `main`**: `find` restricted to `*/src/main/java/*` paths
      (the as-built version flagged its own proof fixture — lessons.md #11)

### Acceptance
- [ ] Script green locally on the closure commit
- [ ] Script green as CI step 4 (run #2 proved it fails closed — the fix run proves it passes open)
- [ ] ArchUnit suite green including the deliberate-violation proof

## S3 — Flyway per-module migrations and schema proof IT ✅

### Work
- [x] `application.yaml` with per-module locations (`classpath:db/migration/{payments,ledger,notifications}`)
- [x] Gap-versioned schema migrations: V101 (payments), V201 (ledger), V301 (notifications)
- [x] `MigrationIT` on Testcontainers PostgreSQL 16 via `@ServiceConnection`, asserting the three schemas
      exist and `payments` starts with zero business tables

### Acceptance
- [ ] `MigrationIT` green in CI — evidence pending
- [ ] Convention documented (design.md §5: payments V1xx, ledger V2xx, notifications V3xx)

## S4 — Compose runtime topology and environment contract ✅ (operator verification pending)

### Work
- [x] `docker/compose.yaml`: postgres 16 (healthcheck), localstack 4 (healthcheck), api-blue/api-green
      (shared env anchor, healthchecks with start period), psp-simulator (chaos env knobs), nginx 1.29
- [x] `docker/nginx/nginx.conf`: Docker DNS resolver (`127.0.0.11 valid=10s`), upstream `resolve` + `zone`,
      keepalive, `proxy_next_upstream error timeout` (lessons #9/#10 baked in)
- [x] `docker/.env.example` as the twelve-factor operator contract (no secrets committed)

### Acceptance
- [x] `docker compose config -q` clean (structurally valid)
- [ ] Operator boot verification (postgres/localstack/nginx/simulator healthchecks green) recorded as
      evidence when a Docker host is available — declared deviation until then

## S5 — Non-root container images ✅ (CI gate evidence pending)

### Work
- [x] Multi-stage Dockerfiles (Maven build → JRE runtime) for api and simulator
- [x] Dedicated uid/gid 10001, `USER 10001`, HEALTHCHECK per image

### Acceptance
- [ ] `image` job green in CI: both images build and the `uid=0` gate passes — evidence pending

## S6 — CI workflow green on the real repository ◐

### Work
- [x] `.github/workflows/ci.yml`: `build` job (checkout → setup-java 25 → boundary check → `mvn -B verify` →
      reports on failure) + `image` job (build both images → non-root gate)
- [x] Third-party actions at trusted major versions; permissions minimal (`contents: read`)
- [x] Run #2 executed and failed closed at the boundary step (the gate proved itself — lessons.md #11)
- [ ] Prod-only boundary fix pushed; **run #3 (or later) fully green** — the epic's headline evidence

### Acceptance
- [ ] A green run link recorded in the acceptance matrix (build + image jobs)
- [ ] No red pipeline on `main` at closure

## S7 — Repository hygiene ◐

### Work
- [x] Conventional commits adopted from `chore(repo): …` onward (violated only by the two initial history commits)
- [x] `.dockerignore` covers build context (target/, .git, docs, env)
- [ ] `uploads/` directory removed from the repository (1.1 MB conversation debris on `main`)
- [ ] Updated `.gitignore` lands (covers `uploads/`, `internal-notes/`)
- [ ] `LICENSE` (MIT) present at repository root

### Acceptance
- [ ] `git ls-files | grep uploads` empty; `.gitignore` matches the workspace version; LICENSE in the root listing

## S8 — Foundation documents set ✅

### Work
- [x] `README.md` (EN, name etymology, guarantees, quickstart, milestones), `AGENTS.md` (binding rules),
      `CHANGELOG.md`
- [x] `docs/`: design.md (EN canonical v1.0.2) + design-ptbr.md (approval snapshot), coding-standards,
      data-model-decisions, lessons (incl. #11), testing-playbook (28-scenario catalog), observability, slos,
      load-test-baseline, release-runbook, twelve-factor

### Acceptance
- [x] All documents present and cross-linked; 100% English
- [ ] Design doc section references re-checked against the as-built tree during closure (fix drift in the same set)

## S9 — Acceptance matrix evidence and M0 formal closure ☐

### Work
- [ ] Every row of `tasks/m0-acceptance-matrix.md` carries evidence (link or command output reference)
- [ ] Declared deviations updated (wrapper status, operator boot verification)
- [ ] README "Current state" flipped to M0 closed; CHANGELOG Unreleased finalized
- [ ] Closure lesson captured in `docs/lessons.md` if closure surfaced anything new

### Acceptance
- [ ] Matrix has zero `pending` cells
- [ ] A tagged-candidate `main` with green pipeline — M0 declared closed in the matrix header
