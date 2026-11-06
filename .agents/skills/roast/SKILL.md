---
name: roast
description: >-
  Performs a direct critical review of an artifact (file, plan, idea,
  skill, snippet) by holding it to its own goals. Surfaces substantive
  problems first, triages by impact, and stops at findings without
  proposing fixes.
when_to_use: >-
  Use when the user asks to roast, tear apart, poke holes in, or
  critically review something. Triggers: «прожарь», «прожарка»,
  «разнеси», «продырявь», «найди дыры», «найди слабые места», «найди
  проблемы», «критически разбери», «roast», «tear apart», «poke holes»,
  «critical review», «what's wrong with this».
---

# roast

Find what is actually broken about the artifact,
not what could be prettier.
Hold it to its own goals
and cite the specific places it falls short.

## When NOT to use

- Polish or proofreading passes.
- Validation requests like "does this look right?" —
  those are reviews, not roasts.
- Rewrites —
  roast critiques,
  it does not produce a new version.

## Inputs

Identify the target before starting:

- A file or section in a file → read it in full.
- A snippet pasted in the conversation → quote it back when citing.
- A plan, idea, or design → capture the user's stated claims and
  scope.
- A diagram, screenshot, or visual artifact → describe the visual
  region you are pointing at
  (e.g. "the top-right panel",
  "the third step of the diagram")
  instead of a line number.
- A running system or behavior → cite by reproduction —
  the command run,
  the input,
  the observed output.

If the target spans more than one artifact
(a whole repo, several files, a vague "our process"),
ask the user to narrow scope before starting.

## What counts as a finding

A finding has three parts:

- **Where** — the specific line,
  section,
  claim,
  or behavior it points to.
- **What is wrong** — quoted or paraphrased,
  then named clearly.
- **Why it matters** — a one-sentence consequence:
  «without this, X breaks»,
  «this leads to Y wrong outcome».

If you cannot articulate all three,
it is not a finding —
drop it.
This is the test against manufactured complaints.

## Procedure

1. **Read the artifact in full and establish the bar.**
   In order:
   - **Stated bar** — explicit goals,
     rules,
     scope,
     and examples in the artifact itself.
     Use it as-is when present.
   - **Implicit bar** — if nothing is stated,
     derive what the artifact is obviously trying to do from its
     content and surrounding context.
     Name the inferred bar in one sentence at the top of the roast
     so the user can correct it.
   - **Ask** — if the bar is genuinely ambiguous,
     ask the user one question before starting:
     «What is this trying to achieve?»
2. **Hunt in this priority order.**
   Cover each level before moving to the next:

   1. **Self-contradictions** —
      where does the artifact violate its own rules?
   2. **Empty claims** —
      imperatives or rules with no enabling mechanism
      ("confirm X" with no method,
      "always do Y" with no recipe).
   3. **Examples that break the rules they demonstrate.**
   4. **Edge cases inside the artifact's own scope** that go
      unaddressed.
   5. **Bad environmental assumptions** —
      assumes git repo,
      network access,
      specific OS,
      specific tooling,
      without saying so.
   6. **Missing features the artifact's principles imply** —
      what its stated stance logically demands but doesn't deliver.
   7. **Style and redundancy** —
      lowest priority,
      include only after the substantive levels are covered.
3. **Triage findings by impact, not by hunt category.**
   - **Substantive** — the artifact fails to deliver something it
     explicitly claims,
     or a load-bearing mechanism is broken.
   - **Functional gaps** — a rule is unclear,
     fragile,
     or only actionable by guessing.
   - **Minor** — redundancy,
     smell,
     or cleanup that does not affect correctness.

   The hunt categories from step 2 are a search aid,
   not a severity label.
   A self-contradiction (category 1) can be Minor if its impact is
   cosmetic;
   redundancy (category 7) can be Substantive if it actively misleads.
4. **Cite specifics.**
   For each finding,
   point at the line, section, or passage.
   Quote the artifact when its wording is the problem.
5. **Stop at findings.**
   Do not propose fixes inside the roast.
   End with one line:
   ask the user which findings to act on,
   or — only if scope is genuinely unclear —
   ask a single clarifying question.

## Rules

- **Be direct.**
  No "this is great, but" framing.
  No softening qualifiers.
  If something is broken,
  say it.
- **Hold the artifact to its own bar.**
  Do not import external preferences or stylistic standards;
  judge against the goals,
  scope,
  and rules the artifact itself states or implies.
  **Exception:** factual errors —
  wrong syntax,
  fabricated commands or APIs,
  false claims about how a tool / spec / system actually works —
  are always findings,
  regardless of whether the artifact stakes out a position on them.
  Reality is the bar there.
  When in doubt about a claim,
  verify it before flagging
  (or before letting it pass).
- **Quote, do not paraphrase,** when the wording is the issue.
- **Do not mix critique with implementation.**
  Roast and fix are separate turns.
- **If you find nothing substantive, say so plainly.**
  Do not pad with manufactured complaints to look thorough.
- **No warm-bath summary alongside findings.**
  Don't end a roast that has findings with «but overall this is
  solid».
  If the artifact has no findings at all,
  one neutral sentence stating that is fine —
  see `Reporting`.
- **Self-roast: heightened skepticism.**
  When the artifact is something you (the assistant) authored in this
  session,
  read it as if a stranger wrote it.
  In-session reasoning has already justified its choices to you,
  which biases you toward leniency.
- **Scale to the artifact.**
  Short artifacts get short roasts.
  For long artifacts,
  surface the top issues per severity bucket —
  do not enumerate every instance.
  Keep individual findings to 2–3 sentences;
  the user can ask for depth in a follow-up.

## Reporting

Output structure:

1. (Optional) one line on what you read and how you scoped the review.
2. **Substantive** findings — numbered, each with a citation.
3. **Functional gaps** — numbered, each with a citation.
4. **Minor** — numbered, each with a citation.
5. One closing line —
   ask which findings to act on,
   or one clarifying question.

Omit any severity section that has no findings.
If the artifact has no substantive issues at all,
say so in one line and stop.

## Example finding shape

A finding reads like:

> 2. **Examples violate their own rule.**
>    The artifact states aqua-first as a hard rule
>    (`Hard rules`, rule 3),
>    but the example block on lines 122–124 demonstrates a `cargo:`
>    entry for a tool that is in the aqua registry —
>    the reader is shown the wrong path.

Three things present:

- citation —
  `Hard rules` rule 3, lines 122–124;
- evidence —
  the literal contradiction between rule and example;
- impact —
  the reader learns the wrong path.

If any of the three is missing,
go back and add the missing piece —
or drop the finding.
