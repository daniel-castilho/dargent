# M5 — Stretch: Card as 2nd Strategy, k6 hard gate, Redis cache, webhook reprocess — COMMISSIONING PROMPT

> Channel: owner authorized prep 2026-09-08 (overlap); package delivered at E16 closure.
> Status: **AWAITING OWNER COMMISSIONING** (the epics row flip ☐→◐ is the owner's act).
> Companions: `m5-{backlog,sequence,spec,execution-prompt-block1}.md`. Scoping recon:
> `tasks/m5-scoping.md` (in-tree) / `internal-notes/m5-scoping.md` (channel master).

---

## 0. What this epic IS

**The last milestone — the plan completes here.** design.md:632 locks the content: *"Simulated card
(2nd Strategy), k6 as hard gate, Redis read cache, webhook reprocessing via admin — Card added
**without touching** the PIX domain (abstraction proof)."* The M5 lock (design.md:640) breaks now.

**The recon finding that shapes everything:** no rail seam exists yet — the payment flow is
PIX-shaped end-to-end (txid, endToEndId, simulator charge/webhook). So the abstraction proof is
not "plug a second strategy into an interface" — it is **EXTRACT the seam from the live flow with
PIX behavior provably unchanged, then let Card plug in.** That extraction is the intellectual core
of M5.

## 1. The epic in one sentence

**Prove the domain promise made on day 0: a second payment rail lands behind one seam, the PIX
domain untouched; and finish the operations arc — cache on the hot path, load gate with teeth,
webhook re-drill for the on-call.**

## 2. Hard contracts

1. **"PIX untouched" is the north star, proven not narrated**: S0's evidence = full suite + ALL
   ladder ITs + property tests + floors green, zero behavior change, zero PIX-domain file edited
   (diff-audited). If the extraction needs to touch PIX domain logic → STOP + Q-batch (that's a
   design escalation, not a refactor detail).
2. **Card rides the SAME money invariants** — journal double-entry, ladder, idempotent replay,
   outbox, DEBT-guard: no parallel weaker path for card. A card payment that fails an invariant is
   the SAME defect class as a PIX one.
3. **Redis is fail-open for reads, never on the money path**: cache-down = DB fallback, PROVEN by
   IT. The cache is an optimization; its absence must never be observable in correctness.
4. **The k6 gate is a regression tripwire, not an SLA**: threshold seeded from the E16 honest
   numbers (p95 create 37.38 ms spine-on / 17.08 happy; 432–440 rps), margin from measurement;
   untestable-in-CI ambitions stay out; no flaky gate (budget + retry policy via Q-batch).
5. **Admin surfaces follow the house pattern**: 404-hidden default, ladder (unset→404, wrong→401,
   right→200), real actor in audit_log, IT mirrors the E9/E15 rotation ITs.
6. **New runtime env vars via the spec §4 table only** (each: rationale + safe default); beyond it
   → STOP + Q-batch.
7. **Gates unchanged** (floors owner-fixed; the k6 gate is an ADDITION per contract 4).
8. **Chain discipline**: Q-batch first; 1 PR per step/pair; flip = last content commit; citation =
   ONE after; nothing after. Her self-audit valid at block boundaries (TD-36 ratified); channel
   closure audit permanent (TD-35 checklist).
9. **Tag v1.2.0 is an owner call POST-epic** (not in scope; CHANGELOG accumulates).

## 3. Steps (detail in backlog; blocks in sequence)

- **S0 — Strategy seam extraction** (D1): the rail-specific surface (initiate, webhook confirm,
  rail identifiers) moves behind one interface at the payments module edge; PIX implementation
  becomes the first strategy. Proof per contract 1.
- **S1 — Card rail** (D2): CardStrategy + simulated card profile in psp-simulator (auth/decline
  minimal — the proof is the abstraction, not card realism); card payments through the SAME
  invariants; card ITs (happy + decline + invariant spot-checks).
- **S2 — Redis read cache** (D3): ONE hot read path (channel leaning: idempotent-replay lookup or
  API-key auth lookup — her Q-batch argues with evidence); TTL, revoke invalidation, hit/miss
  metrics, fail-open IT.
- **S3 — k6 hard gate** (D4): the honest-number tripwire wired into CI (cadence + budget via
  Q-batch); gate failure = red build; flake policy explicit.
- **S4 — Webhook reprocessing admin**: re-drive a stored `webhook_events` row through the intake
  path (dedupe makes it safe); admin ladder per contract 5; observability counters.
- **S5 — Docs + milestone flip + citation**: design.md §M5 unlock (target-state → present tense),
  README money-flow paragraph finally true at "card", testing-playbook scenario updates, CHANGELOG;
  **M5 ☐→✅ = the LAST milestone row flips**; flip → citation → silence.

## 4. Process

1. **Q-batch first** (flagged inline: D1 seam surface, D2 card-sim shape, D3 cache target, D4
   threshold+cadence, S4 key posture). Channel adjudicates with owner on escalations.
2. **Block 1 (S0+S1 — the abstraction proof pair)** → STOP → channel audit → **Block 2 (S2–S5)**.
3. New maturity predictions registered at opening (in register): Perf 3.5→4.0, Testes 4.5→4.75,
   Deployable 4.5→4.75, Scalable 3.0→3.5, Observable 4.0 hold, Reliable 4.75 hold.

## 5. DoD

- [ ] S0 seam extracted, PIX-unchanged proven (diff-audit + suite + ladders + floors)
- [ ] S1 card rail live through the SAME invariants (ITs)
- [ ] S2 cache live on one path, fail-open proven, metrics visible
- [ ] S3 k6 gate biting (green + one red proof)
- [ ] S4 reprocess admin ladder + audit actor
- [ ] S5 truth sweep + **M5 ✅** + flip → citation → nothing after

**Then the channel declares M5 CLOSED — the milestone table complete — and the maturity protocol
runs its final round against the registered predictions.**
