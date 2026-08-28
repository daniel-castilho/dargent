# Foundations & Skeleton M0 — Technical Specification

## Epic E0 — Project Skeleton, Boundary Gates, CI & Baseline Topology

**Priority:** P0
**Companions:** `foundations-m0-backlog.md` · `foundations-m0-implementation-sequence.md` · `ai-software-engineer-prompt-foundations-m0.md`
**Baseline:** workspace-built, reactor-validated (Maven 3.9.11, `mvn -B validate` clean); closure execution on
`main` after `chore(repo): add CI workflow and MIT license; remove stray files; fix missing import`.

---

## 1. Purpose

Deliver and formally close the foundation every Dargent epic builds on: a compilable JDK 25 multi-module
reactor, machine-enforced module boundaries (two independent nets), per-module database ownership from day
one, a compose topology mirroring the target blue-green runtime, and a CI pipeline green on the real
repository — with evidence, not claims.

This specification solves the skeleton deeply. It deliberately does not contain any M1+ behavior.

---

## 2. Scope

### In scope
- Maven multi-module reactor by bounded context (Boot 4.1.1 parent, locked dependency set);
- ArchUnit boundary rules + deliberate-violation proof fixture + import/FQN shell gate (production scope);
- Flyway per-module locations with gap-versioned schema migrations and a real-PostgreSQL proof IT;
- Compose topology (postgres 16, localstack, api-blue/green, psp-simulator, nginx) + environment contract;
- Non-root container images for api and simulator;
- CI two-job pipeline (build gate, image gate) green on `main` and on a PR;
- Repository hygiene (uploads removal, .gitignore, LICENSE) and acceptance-matrix closure.

### Out of scope
- Payment lifecycle behavior of any kind (E1/E3/E4/E5/E8);
- Messaging: SNS/SQS adapters, queue provisioning, relay (E6) — LocalStack runs empty by design;
- REST endpoints, security filters, idempotency (E3/E4);
- Deploy scripts, canary flip, shutdown-under-load (E12);
- Quality/security gates beyond build+image: SpotBugs, OWASP, JaCoCo, Trivy, SBOM, CodeQL, k6 (E13);
- Maven wrapper generation (declared deviation; developer machines may add it without a spec change).

---

## 3. Architectural constraints

### 3.1 Module map and dependency direction

```
dargent-parent (pom, spring-boot-starter-parent 4.1.1)
├── modules/shared          ← depends on NOTHING (zero compile-time framework deps)
├── modules/payments        ← shared only
├── modules/ledger          ← shared only
├── modules/notifications   ← shared only
├── apps/api                ← all modules (wiring only; no domain logic, ever)
└── apps/psp-simulator      ← NOTHING shared (the outside world; its own Boot app)
```

Enforcement: ArchUnit semantic rules per module + `scripts/check-boundaries.sh` import/FQN grep in CI.
The two nets are complementary: ArchUnit reasons about semantics; the script runs before Maven in CI
(fail-fast, framework-independent). Lesson #11 governs: the shell gate scans `*/src/main/java/*` only.

### 3.2 Package shape (inside a module — fixed for all future epics)

```
io.dargent.<module>/
├── domain/            (model, port/in, port/out — zero framework imports, ArchUnit-enforced)
├── application/       (use cases; transaction boundaries live here from M1)
└── adapter/           (in: rest, messaging; out: persistence, psp, messaging)
```

M0 seeds live strictly in `domain/model` (`Money`, `EventEnvelope` in shared; `PaymentStatus` in payments;
`EntryDirection` in ledger) and `architecture/` in test scope.

### 3.3 Cross-cutting invariants established here and never relaxed

- `apps/api` contains no domain logic (AGENTS.md §2) — M0 honors this with only `DargentApiApplication`.
- `shared` junk-drawer prevention: `Money` (money VO), `EventEnvelope` (broker-agnostic event contract) only;
  anything new needs the two-modules-plus-no-business-rules test (AGENTS.md §2.1).
- Sources 100% English; secrets only via environment; forward-only migrations.

---

## 4. Locked dependency set (changes require explicit approval)

| Dependency | Version | Scope | Why |
|---|---|---|---|
| `spring-boot-starter-parent` | 4.1.1 | parent | Framework, Jackson 3, managed plugin versions |
| `spring-boot-starter-web` | managed | api, simulator | MVC + embedded server |
| `spring-boot-starter-actuator` | managed | api, simulator | Health model |
| `spring-boot-starter-jdbc` | managed | api | `JdbcClient` for MigrationIT (and ledger from M2) |
| `flyway-core` + `flyway-database-postgresql` | managed | api | Per-module migrations |
| `postgresql` (driver) | managed | api (runtime) | PostgreSQL 16 |
| `spring-boot-starter-test` | managed | all (test) | JUnit 6, AssertJ, Spring Test |
| `archunit-junit5` | 1.4.1 | all (test) | Boundary semantics |
| `spring-boot-testcontainers` | managed | api (test) | `@ServiceConnection` |
| `testcontainers-bom` | 2.0.1 (import) | parent dependencyManagement | Boot 4 no longer manages TC |
| `testcontainers-junit-jupiter` / `testcontainers-postgresql` | via BOM | api (test) | TC 2.0 module names (`org.testcontainers.postgresql` package) |
| `spring-context` | managed | payments (test only) | Annotation availability for the violation fixture |

Any addition = PR with rationale + approval; the spec is updated in the same change.

---

## 5. Exact artifact contracts

### 5.1 Reactor (`pom.xml`)
- Modules registered in build order: shared → payments → ledger → notifications → api → psp-simulator.
- Child POMs two levels deep declare `<relativePath>../../pom.xml</relativePath>` (net-grandchild layout).
- Apps pin `<finalName>` (`dargent-api`, `dargent-psp-simulator`) — Dockerfiles depend on exact names.
- Failsafe bound to `verify`, includes `**/*IT`, `failIfNoTests=false` (modules without ITs stay green).
- JaCoCo/SpotBugs deliberately absent (E13); a pluginManagement comment marks the plan.

### 5.2 Boundary script (`scripts/check-boundaries.sh`)
- Checks, in order: payments↛{ledger,notifications}; ledger↛{payments,notifications};
  notifications↛{payments,ledger}; shared↛business; psp-simulator↛any `io.dargent.*`;
  domain purity (no `org.springframework|jakarta.persistence|jackson|awssdk` imports) in
  `find modules -type f -path '*/src/main/java/*' -path '*/domain/*'` — **production paths only**.
- Fails closed: any match → readable violation report + exit 1; clean pass → `check-boundaries: OK`.
- Runs as CI `build` step 4, before Maven (fail-fast); `BadDomainFixture` (payments test scope) is the
  intentional ArchUnit-proof violator and MUST NOT be "fixed" — the script simply never sees it.

### 5.3 CI workflow (`.github/workflows/ci.yml`)
- `build` job: `actions/checkout` → `actions/setup-java` (temurin, **25**, maven cache) → boundary script →
  `mvn -B verify` → upload `**/target/*-reports/**` on failure only.
- `image` job (needs build): build `dargent-api:<sha>` (apps/api/Dockerfile) and
  `dargent-psp-simulator:<sha>` (apps/psp-simulator/Dockerfile) → non-root gate
  (`docker run --rm --entrypoint id` must fail on `uid=0`).
- `permissions: contents: read`; trigger: push to main + all PRs; wrapper absence is why `mvn` (not `./mvnw`).

### 5.4 Container images (apps/*/Dockerfile)
- Multi-stage: `maven:3.9-eclipse-temurin-25` build (copy root pom + modules + apps; BuildKit cache mount
  for `/root/.m2`) → `eclipse-temurin:25-jre-alpine` runtime.
- Non-negotiables: dedicated group/user 10001, `USER 10001`, `HEALTHCHECK` hitting the actuator health
  endpoint with start period, `EXPOSE` of the app port, exact `finalName` jar path.
- Digest-pinned bases and layered-jar extraction are E14 work — explicitly deferred.

### 5.5 Persistence (Flyway)
- Locations (apps/api `application.yaml`): `classpath:db/migration/payments, ledger, notifications` —
  each module's migrations travel **inside the module jar** (`modules/*/src/main/resources/db/migration/...`).
- Version convention: payments V1xx, ledger V2xx, notifications V3xx (gap numbering = ownership signal;
  no central renumbering ever).
- M0 migrations are schema-only (`CREATE SCHEMA IF NOT EXISTS`); business tables arrive with their epic,
  in expand/contract discipline (D16).
- Proof: `MigrationIT` — real PostgreSQL 16 via Testcontainers `@ServiceConnection`; asserts the three
  schemas exist and `payments` has zero business tables (the M0 honesty check: no scope creep in DDL).

### 5.6 Compose topology (`docker/`)
- Services: `postgres` (16-alpine, healthcheck `pg_isready`), `localstack` (4, health on sqs availability),
  `api-blue`/`api-green` (same image, env anchor shared, healthchecks with start period 30s),
  `psp-simulator` (chaos env knobs passed through), `nginx` (1.29-alpine, config bind-mounted read-only).
- `nginx.conf`: `resolver 127.0.0.11 valid=10s` + upstream `resolve`/`zone` (recreated fleets are picked
  up — lessons #9), `keepalive 32`, `proxy_next_upstream error timeout`, forwarded headers. Static
  round-robin over blue/green in M0; canary flip scripts are E12.
- `.env.example` = the operator contract (DB, AWS endpoint/keys-as-test, PSP URL/secret, chaos knobs,
  fee bps); no real `.env` ever committed.

### 5.7 Simulator seeds (`apps/psp-simulator`)
- Own Boot app, port 8090, actuator health/info only; chaos knobs bound to `dargent.psp.chaos.*` from env.
- No `io.dargent.*` imports outside its own package (the shell gate checks this) — it is the outside world.

---

## 6. Verification matrix (maps to `tasks/m0-acceptance-matrix.md`)

| # | Criterion | Proven by |
|---|---|---|
| 1 | Reactor builds | `mvn -B validate` (local) → `mvn -B verify` (CI, JDK 25) |
| 2 | CI green on real PR | Actions run link (build + image jobs), PR path exercised |
| 3 | ArchUnit gate proven | `boundary_gate_rejects_a_deliberate_domain_violation` green |
| 4 | Import/FQN net | `bash scripts/check-boundaries.sh` local + CI step green |
| 5 | Per-module schemas | `MigrationIT` green in CI |
| 6 | Compose topology | `docker compose config -q` + operator boot evidence (or declared deviation) |
| 7 | Non-root images | `image` job non-root gate output |
| 8 | Docs & hygiene | Root listing (LICENSE, no uploads/), gitignore, 100% EN docs |

---

## 7. Test requirements

- Unit tests ship with seeds (`MoneyTest` — negative cents, currency normalization, cross-currency refusal,
  non-negative subtraction; envelope invariants) — no mock-heavy ceremony at this layer.
- `MigrationIT` is the only M0 integration test; it must run against real PostgreSQL 16 (never H2) —
  the module-schema assertion is the point.
- The ArchUnit violation fixture lives in test scope, is referenced by its proof test, and is asserted
  via `assertThatThrownBy(...).hasMessageContaining("org.springframework.stereotype.Component")` —
  proving the rule, not just running it.
- No new test infrastructure (singleton containers, WireMock, Awaitility) until the epics that need it.

---

## 8. Risks & troubleshooting

### 8.1 Known risks
| Risk | Likelihood | Mitigation |
|---|---|---|
| First JDK 25 compile surfaces language-level breakage | Medium | Seed code is conservative Java; triage recipe below; lessons.md entry if non-obvious |
| TC 2.0 artifact/package drift | Low (pre-fixed) | BOM 2.0.1 + `org.testcontainers.postgresql` imports locked in spec §4 |
| Boundary gate regression (test-scope scan returns) | Medium | Lesson #11 + spec §5.2 contract + CI step order |
| LocalStack healthcheck flakiness in operator boot | Medium | Healthcheck retries 12×10s; LocalStack stateless anyway — boot verification is best-effort evidence |

### 8.2 Compile-failure triage recipes (first CI green)
1. **`release version 25 not supported`** → setup-java version or parent `java.version` mismatch — both pinned
   (`25`); check the runner actually used temurin 25.
2. **ArchUnit packages unresolved** → `archunit-junit5` 1.4.1 scope/test in every module using it; verify
   `dependencyManagement` in the root.
3. **TC imports red** → artifact names must be `testcontainers-junit-jupiter` / `testcontainers-postgresql`
   and imports `org.testcontainers.postgresql.PostgreSQLContainer` (2.0 layout — pre-fixed in tree).
4. **Failsafe runs nothing / runs unit tests** → includes `**/*IT` and `failIfNoTests=false` in root
   pluginManagement; suffixes `*Test` vs `*IT` respected.
5. **Anything else** → fix forward in a small commit + `docs/lessons.md` entry (next number, top of file).

---

## 9. Closure checklist (epic Definition of Done)

- [ ] §6 verification matrix fully evidenced in `tasks/m0-acceptance-matrix.md` (zero `pending`)
- [ ] Green pipeline on `main` and on a PR (links recorded)
- [ ] `uploads/` gone; LICENSE present; `.gitignore` current; conventional commits from closure onward
- [ ] README "Current state" truthful; CHANGELOG finalized for M0
- [ ] Lessons updated with anything closure taught us
- [ ] Spec/backlog/sequence updated per actual execution (as-built notes), deviations declared with owners
