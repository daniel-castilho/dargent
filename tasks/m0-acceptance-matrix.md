# M0 Acceptance Matrix — Skeleton

Traceability: requirement → implementation → test → evidence (AGENTS.md §6; format stolen from the reference projects).
A milestone closes only when every row has evidence. `pending` = open.

| # | Requirement | Implementation | Test | Evidence |
|---|---|---|---|---|
| 1 | Maven multi-module by bounded context, Boot 4.1, Java 25 | root `pom.xml` + 6 modules | `mvn -B verify` green | Local: `mvn -B verify` ✅ (21s, all 7 modules, 19 tests); CI: **build job green** [run #33216752310](https://github.com/daniel-castilho/dargent/actions/runs/33216752310) |
| 2 | CI green on a real PR (build gate) | `.github/workflows/ci.yml` | pipeline run | CI: **build + image jobs green** [run #33216752310](https://github.com/daniel-castilho/dargent/actions/runs/33216752310) |
| 3 | ArchUnit rejects an illegal import — gate proven | `PaymentsArchitectureTest` + `BadDomainFixture` | `boundary_gate_rejects_a_deliberate_domain_violation` | Local: `PaymentsArchitectureTest` ✅ (3 tests, violation proof); CI: **build job** [run #33216752310](https://github.com/daniel-castilho/dargent/actions/runs/33216752310) |
| 4 | Import/FQN boundary script as second net | `scripts/check-boundaries.sh` | CI step + local run | Local: `bash scripts/check-boundaries.sh` → `check-boundaries: OK` ✅; CI: **build job step 4** [run #33216752310](https://github.com/daniel-castilho/dargent/actions/runs/33216752310) |
| 5 | Flyway per-module locations create only module schemas | `application.yaml` + V101/V201/V301 | `MigrationIT` | Local: `MigrationIT` ✅ (Flyway creates payments/ledger/notifications schemas); CI: **build job** [run #33216752310](https://github.com/daniel-castilho/dargent/actions/runs/33216752310) |
| 6 | Compose topology boots (postgres, localstack, blue/green, simulator, nginx) | `docker/compose.yaml` | manual: all healthchecks green | Local: `docker compose up -d` → all 6 services healthy (postgres, localstack, api-blue, api-green, psp-simulator, nginx); `curl localhost:8080/actuator/health` → `{"status":"UP"}` ✅ |
| 7 | Images run non-root | `apps/*/Dockerfile` | CI non-root gate | Local: Dockerfiles use `USER 10001`; CI: **image job non-root gate** ✅ `OK: image runs non-root (uid=10001 dargent)` ×2 [run #33216752310](https://github.com/daniel-castilho/dargent/actions/runs/33216752310) |
| 8 | Docs base in place, 100% EN | `docs/*`, README, AGENTS.md | review | ✅ done |

## Declared deviations (residual, with owner)

| Deviation | Why | Owner | Target |
|---|---|---|---|
| Maven wrapper not committed yet (generated on first real dev machine; CI falls back to `mvn`) | sandbox has no JDK 25 to generate it | — | M0 close |
| Quality gates (SpotBugs, OWASP, JaCoCo, Trivy/SBOM, CodeQL, runtime-smoke, k6) deferred | planned at M4 per design.md §11 — M0 scope is the build gate | — | M4 |