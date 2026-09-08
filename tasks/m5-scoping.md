# M5 — SCOPING (channel-side, in progress; owner authorized prep 2026-09-08)

Status: RECON DONE (round 1) · deep scoping continues while she executes E16 · package 5/5 targets
owner review after E16 closes (overlap plan). Nothing here is commissioned to the engineer yet.

## What design.md locks (the contract, verbatim references)

design.md:632 — M5 = Simulated card (2nd Strategy), k6 as hard gate, Redis read cache, webhook
reprocessing via admin; "Card added **without touching** the PIX domain (abstraction proof)".
design.md:640 — "card/Redis **locked at M5**" (the lock breaks NOW). README:197 milestone row ☐.

## Recon findings (tree, 2026-09-08)

1. **Maven layout**: `modules/{shared,payments,ledger,notifications}` + `apps/{api,psp-simulator}`.
   Domain lives in modules; apps are deployables.
2. **NO rail seam exists yet**: no `Strategy`/`Rail`/`Gateway` interface found; the payment flow is
   PIX-shaped end-to-end (txid, endToEndId in `PspProfile`, `EndToEndIdGenerator`, simulator
   `charge/`+`webhook/`). **M5's first real work = EXTRACT the Strategy seam from the live flow**
   (proof = extraction lands with PIX behavior unchanged: suite + floors + ladders green), THEN
   CardStrategy plugs in.
3. **Zero Redis/cache surface** (code, compose, poms) — greenfield. Cache policy is a fresh decision.
4. **Webhook reprocessing seam**: `WebhookController` intake (HMAC fail-closed, anti-replay, dedupe,
   conditional confirm) + `webhook_events` storage + E9/E15 admin pattern (OutboxAdminController
   precedent + `DARGENT_OUTBOX_ADMIN_KEY` posture). Reprocess = admin re-run of intake for a stored
   event, dedupe makes it safe; NOT outbox republish (that direction already exists via
   republish-outbox).
5. **psp-simulator has no card surface** — card sim = new simulator capability (auth/decline/charge)
   or a second simulator profile (`PspProfile` precedent).

## The 4 decision areas for the package (owner + Q-batch)

| # | Decision | Channel leaning (to adjudicate) |
|---|---|---|
| D1 | **Strategy extraction shape**: interface at payments module edge; what exactly is rail-specific (initiate/confirm webhooks/idempotent replay?) vs shared (ledger, outbox, ladder) | Extract minimal seam proven by "PIX unchanged" evidence; card = the proof it's a real seam |
| D2 | **Card sim scope**: extend psp-simulator with card charge/auth/decline profile vs new container | Extend (PspProfile precedent; one simulator, two rails) |
| D3 | **Redis cache policy**: WHAT caches (merchant key lookups? idempotent replay reads? projection reads?), TTL, invalidation, fail-open posture (Redis down = DB fallback, never fail-closed for reads) | Start with ONE hot read path, documented hit/miss metrics; cache is an optimization, not a dependency |
| D4 | **k6 hard-gate threshold**: the E16 spine-on honest number becomes the floor; gate = perf regression tripwire on the money path (not an absolute SLA) | Gate on p95 create + error rate, margin vs the measured baseline; hardware noted; runs on push (budget!) |

Plus: webhook reprocessing semantics (admin-only, `DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY`? reuse
outbox admin key posture; ladder mirrors E9 Q11 + E15 R3), docs (design.md §M5 unlock + README
target-state paragraph becomes present tense).

## Milestone table consequence

README:197 + design.md:632 M5 row flips ☐→◐ at E16 close (M5 COMMISSIONED) — the last milestone.
