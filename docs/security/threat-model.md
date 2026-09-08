# Threat Model — Dargent PIX payment backend (E13 S5, R4)

Scope: the deployed system as built through E13 — modular monolith (Java 25, Spring Boot 4.1),
PostgreSQL 16 (schema-per-module), SNS/SQS FIFO via LocalStack, NGINX blue-green on bare metal.
Method: STRIDE across the six surfaces below. Every cell cites an EXISTING control by name
(IT class, config key, script, or migration) — intentions are not controls. Gaps are declared
as DEBT rows in [`AGENTS.md` §8](../../AGENTS.md) (same commit as this document) with a
disposition; "accepted" is a disposition, not an excuse to stay silent.

Surfaces:

1. **Public webhook** — `POST /webhooks/psp` (unauthenticated by design; trust comes from HMAC)
2. **Merchant API + API keys** — `/v1/**` with `Authorization: Bearer` API keys
3. **Admin surfaces** — `/v1/outbox/*` (`DARGENT_OUTBOX_ADMIN_KEY`) and ledger admin
   (`DARGENT_LEDGER_ADMIN_KEY`: rebuild/proof/settlements)
4. **PSP seam** — outbound `POST /cobs` + inbound webhook; the PSP simulator is the outside world
5. **Compose + NGINX edge** — blue-green fleet, management port 9090, NGINX routing
6. **CI + supply chain** — GitHub Actions, Maven deps, images, secrets

---

## 1. Public webhook (`/webhooks/psp`)

| Threat | Control (cited) | Status |
|---|---|---|
| **S**poofing — forged PSP webhook | HMAC-SHA256 over `timestamp + "." + rawBody`, constant-time compare (`WebhookSignatureValidator`; IT: `WebhookIntakeIT` signature legs; AGENTS §4.4) | mitigated |
| **S**poofing — replay of a captured valid webhook | Anti-replay window 5 min on the timestamp leg (`WebhookIntakeIT` expired-timestamp leg; playbook scenario 7) | mitigated |
| **T**ampering — body mutated after signing | Signature is over the RAW body; raw payload persisted verbatim even on invalid signature (attack audit) — `WebhookIntakeIT` tamper legs; AGENTS §4.4 | mitigated |
| **R**epudiation — "PSP never sent / we never received" | `payments.webhook_events` dedupe on `provider_event_id` unique + raw persistence + `payments.audit_log` (`WebhookIntakeIT` duplicate leg; playbook scenario 8) | mitigated |
| **I**nformation disclosure — signature oracle errors | Fail-closed uniform `401` body via the canonical error contract (`ErrorCode.SIGNATURE_INVALID`; no oracle distinction) | mitigated |
| **D**enial of service — webhook flood | **In-app `WebhookAbuseControlFilter` (E15 S1, DEBT-8 resolved): per-IP token-bucket rate limit (429, zero side effects — nothing persisted) + request body cap (413, decided on `Content-Length` before the body/HMAC is consumed)**; raw-payload persistence bounded per event; no unbounded queues on the intake path. NGINX edge rate-limiting remains defense-in-depth for a public deployment | mitigated in-app (NGINX = defense-in-depth) |
| **E**levation — webhook payload becomes a domain entity directly | Anti-corruption layer at the boundary (`WebhookController` → `WebhookIntakeUseCase`; AGENTS §3.6) | mitigated |

## 2. Merchant API + API keys (`/v1/**`)

| Threat | Control (cited) | Status |
|---|---|---|
| **S**poofing — stolen/guessed API key | SHA-256 hashed at rest with indexed prefix; constant-time comparison (`ApiKeyHasher`, `ApiKeyAuthenticationFilter`; prefix lookup O(1); AGENTS §4.3); 52-char base62 entropy | mitigated |
| **S**poofing — revoked key still works | `revoked_at` checked at filter; rotation ITs: `OutboxAdminRotationIT`, `LedgerAdminRotationIT` (revoked predecessor → 401) | mitigated |
| **T**ampering — tenant substitution | `merchant_id` from the credential only; cross-merchant access → `404` not `403` (AGENTS §3.7; `NotificationsApiIT.cross_tenant_rows_are_never_visible_across_credentials`) | mitigated |
| **R**epudiation — "we never created/charged this" | Idempotency-Key mutations (AGENTS §3.3; `CreatePaymentIT` replay legs) + `payments.audit_log` actor key id on every state transition | mitigated |
| **I**nformation disclosure — tenant data across credentials | Per-credential scoping asserted in ITs (`NotificationsApiIT` cross-tenant leg); no cross-credential query path exists by construction (ports take merchantId) | mitigated |
| **I**nformation disclosure — verbose errors/stack traces | Canonical error contract (`ErrorResponse`/`ErrorCode`); `GlobalExceptionHandler` never leaks stack traces; `ProductionLockdownIT` asserts error shape on prod profile | mitigated |
| **D**oS — expensive queries per key | Bounded pagination on list endpoints (`GET /v1/payments` page size caps — `CreatePaymentIT` list legs); no unbounded reads | mitigated |
| **E**levation — a merchant key reaching admin routes | E13 R3: ledger admin endpoints 404-hidden/gated (`LedgerAdminHiddenIT`, `LedgerAdminRotationIT`); outbox admin gated since E9 (`OutboxAdminRotationIT`) | mitigated |

## 3. Admin surfaces (outbox + ledger)

| Threat | Control (cited) | Status |
|---|---|---|
| **S**poofing — merchant key posing as admin | Constant-time compare presented-vs-env key; validation FIRST at the filter, env match never bypasses (`LedgerAdminRotationIT.revoked_predecessor_is_401_*`, `OutboxAdminRotationIT` same leg) | mitigated |
| **S**poofing — route enumeration when admin not configured | Default EMPTY env → 404-hidden (indistinguishable from unknown route): `LedgerAdminHiddenIT`, outbox same semantics | mitigated |
| **T**ampering — admin action on another tenant's data | Ledger admin actions are system-wide by contract (rebuild/proof/settlements are ledger-global); audited with the REAL key id (`ledger_admin_*` rows; `LedgerAdminRotationIT` audit assertions) | mitigated |
| **R**epudiation — "nobody requeued/republished/rebuilt" | `outbox_requeued`/`outbox_republished`/`ledger_admin_rebuild`/`ledger_admin_proof`/`ledger_admin_settlement` audit rows with actor key id, never a sentinel (`OutboxRequeueIT`, `OutboxRepublishIT`, `LedgerAdminRotationIT`, `LedgerSettlementIT`) | mitigated |
| **I**nformation disclosure — admin endpoints reachable without admin key | SecurityConfig `.authenticated()` on all `/v1/**` + controller-level gate; `denyAll()` catch-all (AGENTS §4.1; `SecurityConfig`) | mitigated |
| **D**oS — admin actions flooding (rebuild loops) | Rebuild is a single transaction; republish bounded (max 30-day window, max 500 rows — `OutboxAdminController` bounds) | mitigated |
| **E**levation — admin key granting merchant data access | Admin key is a REAL merchant key with a real identity (no superuser); admin endpoints expose no tenant data — proof/rebuild/settlements are ledger-level | mitigated |

## 4. PSP seam (outbound + simulator)

| Threat | Control (cited) | Status |
|---|---|---|
| **S**poofing — fake PSP responses | Simulator is dev-only; prod PSP contract is the `PspPort`; chaos legs assert fail-closed states (`ReconcilerConfirmIT`; playbook scenarios 25–27) | mitigated |
| **T**ampering — charge result mutated in flight | TLS boundary outside scope of compose; the reconciler re-polls the PSP as truth source (`ReconcilerConsistencyIT`; playbook scenario 26) | mitigated |
| **R**epudiation — "PSP said PAID, we say PENDING" | Reconciler is the arbitration path; divergence resolved by polling; `payments.audit_log` records transitions | mitigated |
| **I**nformation disclosure — PSP secrets in code | `PSP_WEBHOOK_SECRET` from env only; boot fails fast on missing/short secrets (`ConfigValidator` behavior asserted by prod-profile boot tests; AGENTS §4.2) | mitigated |
| **D**oS — PSP down | Timeout + bounded retries with backoff (`PSP_CREATE_MAX_ATTEMPTS`, `PSP_CREATE_BACKOFF_BASE_MS`; `ExpirationSchedulerIT` give-up legs) then FAILED — no infinite retry | mitigated |
| **E**levation — simulator reaching internals | `apps/psp-simulator` shares nothing with the API (AGENTS §2 boundary, ArchUnit-enforced `scripts/check-boundaries.sh`) | mitigated |

## 5. Compose + NGINX edge (blue-green fleet)

| Threat | Control (cited) | Status |
|---|---|---|
| **S**poofing — direct pod access bypassing NGINX | Ports 8081/8082 exposed for blue-green only on the host; NGINX routes the public 80. Direct-port access is a topology concern, not app auth | accepted (bare-metal scope) |
| **T**ampering — NGINX conf drift | `deploy.sh --init` renders from template; the template is never mutated (deploy.sh header contract); runtime conf is a build artifact | mitigated |
| **R**epudiation — "the deploy never happened" | `deploy.sh` prints active color; canary weights rendered into the RUNTIME conf with 30s dwell + smoke probe per step | mitigated |
| **I**nformation disclosure — actuator on the public port | Management isolated on 9090 + `ManagementSecurityConfig` port-matcher; main-port `denyAll` for actuator (`ProductionLockdownIT` Swagger-absent + health-only legs; `ManagementPortIT`) | mitigated |
| **I**nformation disclosure — health details leaking internals | `show-details: never` (+ readiness group `show-details: never`) — asserted in `ProductionLockdownIT` | mitigated |
| **D**oS — new color broken, traffic routed to it | Readiness gate before canary (`deploy.sh --wait-ready` on `/actuator/health`; E13 R2 readiness now includes SNS/SQS — `ReadinessHealthGoodIT`/`ReadinessHealthBadIT`); canary aborts to blue on red | mitigated |
| **E**levation — host escape from containers | API + simulator run non-root; UID-0 gate fails the image build (Dockerfile `USER`; image job "Non-root gate" step) | mitigated |

## 6. CI + supply chain (GitHub Actions, deps, images)

| Threat | Control (cited) | Status |
|---|---|---|
| **S**poofing — a "green" run that never ran the gates | Evidence-lint: every cited run id must resolve via `gh api` and carry its run number (E13 R1; `scripts/evidence-lint.sh` + CI `evidence-lint` job, PR + nightly) | mitigated |
| **T**ampering — vulnerable dependency slipped in | OWASP Dependency-Check CVSS ≥ 7 gate (E13 S1; `dependency-check:aggregate`, NVD keyed, 2 retries then FAIL) + Dependency Review on PRs (`fail-on-severity: high`) | mitigated |
| **T**ampering — vulnerable base image shipped | Trivy pass 2 HIGH/CRITICAL gate on both images (E13 S2; caught CVE-2026-14456 in `libcrypto3` → `apk upgrade`); SBOM CycloneDX artifacts per image | mitigated |
| **T**ampering — action supply chain | Third-party actions SHA-pinned (`actions/cache@55cc834…`, `aquasecurity/trivy-action@ed142fd…`, `github/codeql-action@486fec2…`); first-party `actions/checkout@v7` / `setup-java@v5` ride tags | mitigated (first-party tags: accepted) |
| **R**epudiation — "CI was green at that commit" | evidence-lint enforces id↔number pairs; run ids are immutable GitHub records; amend (c) requires pushed + green run id | mitigated |
| **I**nformation disclosure — secrets in logs/artifacts | Secrets via repository secrets only (`NVD_API_KEY`); no `.env` committed (AGENTS §4.2); no secret ever printed by scripts (grep-clean) | mitigated |
| **D**oS — tool outage silently passes gates | P2/P4 policy: OWASP retries twice then FAILS the job; Trivy gate fails; nothing passes silently (E13 spec §3) | mitigated |
| **E**levation — static analysis findings ignored | CodeQL `security-extended` on push/PR main + SARIF upload; SpotBugs Medium+ gate with rationale-per-exclusion file (E13 S0) | mitigated |
| **T**ampering — suppression sprawl | `owasp-suppressions.xml` committed EMPTY (E13 S1); every future entry needs rationale + review date (P4) | mitigated |

---

## Catalog pointer

Playbook scenario 28 (production lockdown) is satisfied by `ProductionLockdownIT` (E11 S3) —
the testing-playbook matrix row carries the link since E11; re-verified in E13 S5.

## Gaps → debt

- **DEBT-8 (RESOLVED 2026-09-08, E15 S1)**: ~~no rate limit on the public webhook route~~ closed
  in-app: `WebhookAbuseControlFilter` — 429 per-IP token bucket + 413 body cap on `POST /webhooks/psp`
  only, control order and zero-side-effect contracts carved by `WebhookRateLimitIT` /
  `WebhookBodyCapIT`. AGENTS §8 row → RESOLVED with the commit.
- All other cells: mitigated by cited, test-enforced controls.
