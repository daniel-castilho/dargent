# Handoff Definition of Done — reusable appendix for every execution prompt

**Rule zero — zero-from-memory:** EVERY number, sha, run id, and test count in a handoff must be
copy-pasted from a command output included in the handoff. If you cannot paste the command that
produced it, the claim does not go in. A number written from memory is a hypothesis, and unlabeled
hypotheses in closing handoffs are defects (TD-13 class).

## 1. Mandatory evidence block (paste RAW outputs)

```bash
# The exact chain — nothing else
git log --oneline <channel-base>..HEAD

# Clean-tree proof
git status --porcelain

# Run pairs (number AND id AND head sha) come FROM here — never from memory
gh run list --limit 5

# Surefire summary for every class you cite
grep -h "Tests run" **/target/surefire-reports/*.txt | tail -20

# Per-cited-class test inventory (the count you must match, or omit counts entirely)
grep -c "@Test" <each cited test file>
```

## 2. Self-audit — run BEFORE sending (any "no" = fix the handoff, not the audit)

- [ ] Every sha resolves: `git cat-file -e <sha>` for each one cited
- [ ] Every (run number, run id) pair appears verbatim in your pasted `gh run list`
- [ ] Every count equals the pasted surefire/grep output (never rounded, never remembered;
      omitting counts is always acceptable — inventing them never is)
- [ ] Every red is IN the table with its pair (a red in a footnote = TD-13)
- [ ] Every "owner approved X" QUOTES the channel message that approved it
- [ ] Every claim about `main` is true of `main`: work that is local-only is labeled
      `LOCAL — awaiting push`, never described as landed
- [ ] No closure claims: closure is adjudicated by the owner channel; handoffs report state + gaps
- [ ] Flip = last content commit; citation = separate final commit, citing a run whose tree IS the
      flip, with nothing landed after it

## 3. Standing definitions

- **Pair** = (test, run number, run id). Ids alone rot; numbers alone drift; both, from `gh run list`.
- **Evidence** = pasted command output. Memory = hypothesis. Hypotheses are labeled as such.
- **Owner sanction** = a quoted channel message. Anything else is attribution; false attribution is
  TD-30 class.
- **LOCAL** prefix = true and unlanded. Never upgrade LOCAL to landed.

## 4. Failure classes this codifies (the E9 record — why each rule exists)

| Rule | The failure it kills |
|---|---|
| §1 `gh run list` | Invented run ids (#167, 33943000000); off-by-one numbers (#155→#156) |
| §1 surefire/grep | Invented counts (21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citing commits that resolve nowhere |
| §2 main-claims | "re-enabled" against a commit message reading "disabled (HOLD)"; Known-Gap narrative about a @Disabled test |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining...")` with no owner authorization |
| §2 no-closure | Four consecutive "E9 CLOSED" declarations from one epic |
