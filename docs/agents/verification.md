# Verification

A green result belongs to a tree: reuse it, and verify again only when the tree changes. `git rev-parse <sha>^{tree}` shows whether a rebase or reword changed it.

## While working

- Iterate: `xvfb-run -a mvn test -Dtest=<Class>`. SingleStepTest also needs `-Psinglestep`, or it runs zero tests and passes.
- Ticket done: `xvfb-run -a mvn -B verify -Psinglestep` once on the committed tip. SpotBugs runs only here, at `verify`.

## Branch done

- The last agent to write the branch verifies each commit once, on a `git archive` export, skipping trees already green. It reports each SHA with its result.
- Orchestrator, reviewers and lander use that report.

## Briefing a reviewer

Give the verify results, and link the implementers' notes and known bugs.

## Landing, when the user asks

1. `but merge <branch> --ff-only --yes`. This pushes `main`.
2. Report "pushed" with the SHA.
3. In the background, find the run with `gh run list --commit <full sha>` (empty for a short SHA, and for a few seconds after the push: retry), then `gh run watch <id> --exit-status`.
4. Report the CI result when it exits.
