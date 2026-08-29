# PSP Simulator API E2 — Implementation Sequence

## Epic E2 — The Fake Stripe: Charges, Payer Bank, Signed Webhooks & Chaos Knobs

**Companions:** `psp-simulator-e2-spec.md` · `psp-simulator-e2-backlog.md`
**Rule:** Complete each step's acceptance and verification before starting the next. Do not invent E3+ scope.
**Process rule:** S2 and S5 are test-first (red → green → refactor). The simulator is the only place in the
codebase where a production `Thread.sleep` is legal (latency chaos) — never in test assertions.

---

## Global execution rules

1. Small reviewable vertical commits; story-sized.
2. Read the story acceptance before coding; tests ship with the change.
3. No dependency beyond spec §4 without explicit approval (WireMock-for-stub-receiver is the only
   pre-declared candidate, per backlog S7).
4. A red `main` baseline stops work; regressions are diagnosed before new commits.
5. After each step: update backlog checkboxes, note deviations here.
6. The epic touches ONLY `apps/psp-simulator` (+ its tests). Any diff outside it (except tasks/docs) is a
   scope violation — revert and re-read the prompt.

### Fast verification used throughout

```bash
mvn -B -pl apps/psp-simulator -am test
```

### Full verification (reactor + images still healthy)

```bash
mvn -B verify
```

### Scope discipline check (run before every push)

```bash
git diff --stat main -- apps/api modules | wc -l   # expect 0 lines: this epic never touches them
bash scripts/check-boundaries.sh                    # the simulator import net stays green
```

---

## Step 0 — Baseline lock

### Stories: S0
### Actions
1. Confirm CI green (E1 closure run #33225043138 is the reference) and local simulator module verifies.
2. Read spec §5 (contracts — memorize the webhook table) and §6 (chaos semantics).
3. Inventory the M0 seeds: `application.yaml` chaos bindings, actuator, port 8090. Keep names stable.

### Done when
- Local verify green; contract questions resolved (none expected — ask, don't guess).

---

## Step 1 — Profile, Clock, chaos bindings (S1)

### Actions
1. Config props (`PspProfile`, `WebhookSecret`, `ChaosProperties`), `Clock` bean, seedable `Random`.
2. Binding unit test: defaults from `application.yaml`, overrides via properties.

### Done when
- All knobs resolvable from env with M0-compatible names; zero `Instant.now()` outside the Clock bean.

### Verify
```bash
mvn -B -pl apps/psp-simulator -am test
```

---

## Step 2 — Charge domain + store + ids (S2) — TESTS FIRST

### Actions
1. Write `ChargeTest` (lifecycle rules incl. expiry boundary via injected Clock/params), 
   `EndToEndIdGeneratorTest` (format property over 100 samples), `ChargeStoreTest` (duplicate txid) — 
   watch them fail.
2. Implement `Charge`, generators, `ChargeStore` per spec §5.3 + §3.1. No Spring types.

### Done when
- Pure unit layer green; no HTTP involved yet; format `^E[A-Za-z0-9]{31}$` proven.

### Verify
```bash
mvn -B -pl apps/psp-simulator -am test
```

---

## Step 3 — Charge endpoints (S3)

### Actions
1. `ChargesController`: `POST /cobs` + `GET /cobs/{txid}` per spec §5.1/§5.2; errors as `{code, message}` JSON.
2. MockMvc slice tests: happy path, txid shape, duplicate 409, bad amount/expiry/callbackUrl 400, unknown 404,
   EXPIRED computed on GET.

### Done when
- Endpoint layer green against the Step 2 domain; response shapes byte-match the spec examples.

### Verify
```bash
mvn -B -pl apps/psp-simulator -am test
```

---

## Step 4 — Payer bank (S4)

### Actions
1. `POST /cobs/{txid}/payments` per spec §5.3 rules; dispatch hook behind an interface (the dispatcher arrives
   in Step 5 — stub it in tests with a recording fake).
2. Slice tests: 404 / 409 `charge_expired` / 409 `already_paid` / success (status PAID, ids present) /
   dispatch-triggered-once.

### Done when
- All rule branches green; the success path triggers exactly one dispatch (pre-chaos).

### Verify
```bash
mvn -B -pl apps/psp-simulator -am test
```

---

## Step 5 — Signer + async delivery engine (S5) — TESTS FIRST

### Actions
1. `WebhookSignerTest` asserting the spec §5.4 test vector byte-exact; `WebhookDispatcherTest` against a local
   stub receiver (record requests, Awaitility for async).
2. Implement signer + dispatcher (bounded executor, single attempt, exact headers/body per §5.4).
3. Wire the dispatcher into the Step 4 hook (replace the recording fake in production config only).

### Done when
- Vector test byte-exact; wire-level delivery (headers + raw body) proven in tests.

### Verify
```bash
mvn -B -pl apps/psp-simulator -am test
```

---

## Step 6 — Chaos wiring + behavior tests (S6)

### Actions
1. Implement the five knobs per spec §6 semantics (duplicate/delay/drop on the dispatcher; error-rate/latency
   on a filter or controller advice). Forced modes drive tests: `duplicate=true`, `dropRate=1.0`,
   `dropRate=0.0` — no probabilistic assertions; `delay` tested with 100 ms.
2. Behavior tests per knob (dispatcher-level, fast).

### Done when
- Every knob demonstrably changes behavior in a test; defaults all-off (M0 contract).

### Verify
```bash
mvn -B -pl apps/psp-simulator -am test
```

---

## Step 7 — Integration proofs (S7)

### Actions
1. Full lifecycle IT: create → get → pay → webhook captured by the stub receiver; recompute the signature from
   the captured raw body and assert it matches (the receiver-side procedure E4 will implement).
2. Chaos ITs: duplicate → exactly 2 deliveries (same eventId, Awaitility); drop-rate 1.0 → 0 deliveries;
   delay 100 ms → no delivery before the window, one after.
3. Full reactor verify; confirm images unaffected.

### Done when
- Lifecycle IT + chaos ITs green; `mvn -B verify` green; scope check clean.

### Verify
```bash
mvn -B verify
git diff --stat main -- apps/api modules | wc -l   # expect 0
```

---

## Step 8 — Closure (S8)

### Actions
1. Fill `tasks/e2-acceptance-matrix.md` (run links, test names, knob-to-test mapping).
2. Epics ledger E2 → ✅; CHANGELOG entry; design.md §12 sync if drift; lessons entry if warranted.
3. Final commit: `docs(e2): close psp simulator epic — acceptance matrix evidenced`.

### Done when
- Matrix zero pending; CI green on `main`; scope discipline held to the end.

### Verify
```bash
grep -n pending tasks/e2-acceptance-matrix.md   # expect: no output (zero pending)
git status --porcelain                           # expect empty
```

---

## Failure playbooks (stop conditions)

| Symptom | Stop and do this |
|---|---|
| Webhook IT flaky (delivery count varies) | Awaitility timeout too tight or executor saturation — widen poll interval/timeout; NEVER add sleeps; the dispatcher is single-attempt by design |
| Duplicate deliveries arrive in either order | That is allowed (async executor) — the receiver contract is dedupe-by-eventId (E4), not ordering; assert count, not order |
| Delay test is slow | Delay is config; use 100 ms in tests. Never raise the test delay to "make it pass" |
| Signature mismatch on the wire | Charset! Canonical string is UTF-8 bytes of `timestamp + "." + rawBody`; check body re-serialization (the dispatcher must send the EXACT bytes it signed — no pretty-printing) |
| Scope creep into apps/api or modules | Revert; the simulator must be buildable and testable with zero platform changes |
| Error-rate knob makes endpoint tests flaky | Endpoint ITs run with knobs off (defaults); chaos is tested at dispatcher/filter level with forced modes |
