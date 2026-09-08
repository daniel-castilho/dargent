# E16 — Execution Sequence

## Blocks

| Block | Steps | Ends with |
|---|---|---|
| 1 | S0 (v1.1.0 per owner Q3) · S1 Alertmanager · S2 PITR v2 · S3 honest k6 | STOP → channel audit |
| 2 | S4 limiter posture · S5 dependabot · S6 sweep + flip + citation (+ tag tail if position=end) | E16 ✅ declared |

## PR pairing

| PR | Step | Contents |
|---|---|---|
| #25 | S0 (if today) | docs + tag v1.1.0 (tag cut from the docs commit) |
| #26 | S1 | alertmanager + amtool CI step + docs |
| #27 | S2 | PITR v2 harness + drill record |
| #28 | S3 | honest k6 run + comparison table |
| #29 | S4 | limiter posture (docs or code+ITs) |
| #30 | S5 | dependabot.yml |
| #31 | S6 | sweep + CHANGELOG + flip + citation (+ tag tail if position=end) |

Sequencing: S1 before S2/S3 (alerts observable while drills run). S3 needs S0's demo overlay —
already merged (E15 S0); no dependency on tag position. Freeze from flip; citation ONE after;
nothing later.

## Abort conditions (halt + report, no improvisation)

1. Alertmanager routing needing an external sink/secret → stub only; STOP + Q-batch.
2. PITR v2 off-disk replay failing on compose constraints → keep v1 disposition + record the
   constraint; do NOT fake separation.
3. k6 honest run destabilizing the stack (limiter bursts + spine) → reduce VUs, record both runs;
   never discard the honest attempt.
4. Anything pulling M5 territory (card/Redis/gate) into a PR → STOP (M5 fence).
5. Instruction conflict → ask, quote, wait (post-TD-30; TD-36 pending owner ruling — §1.7 of prompt).
