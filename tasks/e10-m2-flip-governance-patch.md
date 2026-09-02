# Governance patch — epics.md E10/M2 flip (rides the OWNER's governance commit)

**For:** the owner's commit that lands the governance workspace files (register, verification,
E10 prompts, this patch). **Source of truth for current state:** `docs/epics.md` @`be91286`
(lines 34–38 of the priority table). Apply these three edits in the same commit:

## Edit 1 — E10 row (line 38): ☐ → ✅

Before:
```
| E10 | Notifications consumer | notifications | E6 | M2 | ☐ |
```
After:
```
| E10 | Notifications consumer | notifications | E6 | M2 | ✅ 2026-09-02 — Block 1 audited (S1–S3 + riders), Block 1.5 remediation (#85–#116: 27 cited reds → #113 `33674334484` Instant binding, #114 `33675295464` poison IT, #115 `33676638904` BD-18, #116 `33677327831` TD-16 correction), Block 2 #118 `33683261976` (S6 read API) → #119 `33684112090` (flip: README/CHANGELOG/matrix) → #120 `33684616551` (citation; run unregistered per #57/#67 precedent) — TD-20 rider pending (register); matrix evidenced (`tasks/notifications-e10-spec.md` §10) — **closes M2** |
```

## Edit 2 — E7 row (line 35): drop the stale trailing note

Replace the trailing `— **M2 stays ◐ until E10**` with `— **M2 ✅ (closed with E10, 2026-09-02)**`.

## Edit 3 — E6 row (line 34): no change needed

("unblocks E5, E7, E10" stays true — history note.)

## Sequencing note

The owner's governance commit lands BEFORE/AFTER the TD-20 rider independently — the epics row
already discloses "TD-20 rider pending (register)", so the ledger stays honest even if the rider
lands in a later push. When the rider closes, no epics.md edit is needed (the register carries it).
