# Foundations & Skeleton M0 — Implementation Sequence

## Epic E0 — Project Skeleton, Boundary Gates, CI & Baseline Topology

**Companions:** `foundations-m0-spec.md` · `foundations-m0-backlog.md`
**Rule:** Complete each step's acceptance and verification before starting the next. Do not invent M1+ scope.

---

## Global execution rules

1. Work in small, reviewable vertical commits; never deliver the epic as one unreviewable change.
2. Read the referenced story acceptance before acting.
3. Add tests with the production change, not at the end.
4. A red baseline or ambiguous locked decision stops work — triage to root cause first.
5. Every workflow/script change updates the acceptance matrix in the same change set.
6. Never add a dependency without explicit approval.
7. After each step, update story checkboxes in the backlog and note deviations here; do not silently
   alter the specification.

### Fast verification used throughout

```bash
mvn -B validate
```

### Full build (first real JDK 25 compile — the epic's headline verification)

```bash
mvn -B verify
```

### Boundary verification (after touching any module layout or the script itself)

```bash
bash scripts/check-boundaries.sh
```

Expected: `check-boundaries: OK`. If it reports a test-scope fixture, stop — you are on a pre-fix tree
(lessons.md #11) and must not "fix" it by weakening the gate.

### Compose verification (structurally, no Docker host required)

```bash
docker compose -f docker/compose.yaml config -q
```

---

## Step 0 — Baseline lock and as-built re-verification

### Stories: S0

### Actions
1. Check out `main` and confirm the tree matches the spec §4 artifact contract (six modules, workflow file,
   script, compose, docs set, seed classes).
2. Run the three verifications above. Record outputs (exit codes) for the matrix.
3. Confirm `.github/workflows/ci.yml` on `main` is the two-job version and the boundary step calls
   `bash scripts/check-boundaries.sh`.
4. Read `docs/lessons.md` #11 end to end before touching anything boundary-related.

### Done when
- All three local verifications exit 0.
- Any doc/tree drift found is listed in the closure commit message (and fixed in it).

### Verify
```bash
mvn -B validate && bash scripts/check-boundaries.sh && docker compose -f docker/compose.yaml config -q && echo BASELINE-OK
```

---

## Step 1 — Repository hygiene push

### Stories: S7

### Actions
1. Remove `uploads/` from the repository (`git rm -r uploads/`).
2. Replace `.gitignore` with the current version (covers `uploads/`, `internal-notes/`, runtime artifacts).
3. Add `LICENSE` (MIT) at the repository root.
4. If the workspace fix is not yet on `main`, apply the boundary-script prod-only change
   (spec §5.2, rule: `find` restricted to `*/src/main/java/*`).
5. Commit as a single hygiene change set.

### Done when
- `git ls-files | grep -c uploads` → `0`.
- Root listing shows `LICENSE`.
- `bash scripts/check-boundaries.sh` → `check-boundaries: OK` on the pushed tree.

### Verify
```bash
git ls-files | grep uploads; echo "uploads exits: $?"   # expect 1
test -f LICENSE && echo LICENSE-OK
bash scripts/check-boundaries.sh
```

---

## Step 2 — CI `build` job green (first real JDK 25 compile)

### Stories: S2, S6

### Actions
1. Push Step 1 and watch the triggered run (or open a PR to prove the PR path — the matrix asks for a real PR).
2. If the boundary step fails: you are not on the fix — stop and re-check Step 1.
3. If `mvn verify` fails to compile: triage with the recipes in spec §8.2 (likely suspects: JDK 25 language
   level wiring, ArchUnit/TC artifact resolution). Fix forward in small commits; update lessons.md if the
   root cause is non-obvious.
4. If `MigrationIT` fails on schema assertions: verify Flyway locations wiring (`application.yaml`) and the
   gap-versioned files before touching the test.

### Done when
- The `build` job completes green: boundary check → unit → slices → `MigrationIT`.

### Verify
```bash
# CI run page: build job green; artifact upload step not triggered
```

---

## Step 3 — CI `image` job green (non-root gate)

### Stories: S5, S6

### Actions
1. Confirm the `image` job ran after `build` and both image builds succeeded.
2. Confirm the non-root gate output shows `OK: image runs non-root (uid=10001 dargent)`.
3. If Dockerfile build fails: check the build-context copy set (spec §5.4 — root `pom.xml`, `modules/`,
   `apps/` are all the context needs) and the `.dockerignore` interference.

### Done when
- Full pipeline (build + image) green on `main` and on a PR.

### Verify
```bash
# CI run page: both jobs green; run link goes to the acceptance matrix
```

---

## Step 4 — Operator topology verification (when a Docker host is available)

### Stories: S4

### Actions
1. `docker compose -f docker/compose.yaml up -d postgres localstack` and wait for healthchecks.
2. `docker compose -f docker/compose.yaml up -d psp-simulator` → `curl localhost:8090/actuator/health`.
3. Bring up `api-blue` (image build) → health green via its healthcheck; then nginx → `curl localhost:8080/actuator/health`
   routes to a fleet.
4. Record the evidence (commands + outputs) in the matrix. If no Docker host is available in this
   environment, keep the deviation declared with an owner — do not fake evidence.

### Done when
- Compose topology demonstrated healthy, or the deviation is explicitly carried with an owner.

### Verify
```bash
docker compose -f docker/compose.yaml config -q && echo COMPOSE-STRUCTURAL-OK
```

---

## Step 5 — Matrix evidence and formal M0 closure

### Stories: S9

### Actions
1. Fill every `pending` cell of `tasks/m0-acceptance-matrix.md`: CI run links, command outputs, file paths.
2. Update declared deviations (wrapper status; operator boot verification outcome from Step 4).
3. Flip README "Current state" to M0 closed (milestones table), finalize CHANGELOG Unreleased.
4. If closure surfaced new hard-won knowledge, add it to `docs/lessons.md` (next number, top of file).
5. Commit `docs(m0): close foundations epic — acceptance matrix evidenced` (or split per file group).

### Done when
- Zero `pending` cells; green pipeline on the closure commit; README/CHANGELOG truthful.

### Verify
```bash
grep -c pending tasks/m0-acceptance-matrix.md   # expect 0
git status --porcelain                           # expect empty
```

---

## Failure playbooks (stop conditions)

| Symptom | Stop and do this |
|---|---|
| Boundary script flags a test fixture | You are pre-fix; apply spec §5.2 — never weaken the gate or delete the fixture |
| `mvn verify` compile error under JDK 25 | Triage per spec §8.2; if it needs a dependency change, stop — approval rule 6 |
| `MigrationIT` schema assertion fails | Check Flyway location wiring and migration file names before touching test logic |
| CI run does not trigger | `.github/workflows/` must exist on the pushed branch; confirm with the Actions tab, not assumptions |
| Flaky IT on first run | Rerun once; if red again, quarantine is FORBIDDEN for a money-path test (playbook §7) — triage |
