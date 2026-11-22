# Coding standards

Review-time rules for this repo. The build already enforces what tools can: `-Xlint:all` and Error Prone under `-Werror`, SpotBugs, Spotless for imports and whitespace (`mvn spotless:apply` fixes it), and the JUnit default timeout. These are the judgement calls.

## Domain language

- Identifiers, comments, test names and user-facing messages use the terms in `GLOSSARY.md`.
- A word on a term's `_Avoid_` line is a violation only when it names that term's concept. `cycle` as a CPU cycle count, or `reset` as the 6502 Reset, is fine.
- A change that introduces a new domain concept adds its glossary entry in the same commit.

## Decisions

- Code follows the ADRs in `docs/adr/`. A change that contradicts one needs a new ADR that supersedes it, not a silent divergence.

## Scope and size

- Do the simplest thing that solves the ticket: no speculative abstractions, options or config.
- Leave code outside the change as it is. Dead code that the change makes visible is mentioned in the review, not deleted.
- A known limit taken on purpose carries `// shortcut: <the limit>, <when to upgrade>`.
- Fix an Error Prone warning rather than suppress it. A `@SuppressWarnings("<Check>")` sits on the narrowest declaration and carries a one-line reason why the check doesn't apply. The same goes for a SpotBugs exclusion in `spotbugs-exclude.xml`.

## Comments

- A comment is one line and gives the why the code can't show.
- Types and public methods carry a one-line Javadoc in the style of the surrounding code, e.g. `/** Maps a Device over whole unmapped pages {@code [base, base + size)}. */`.

## Tests

- A behaviour change and its tests go in the same commit.
- Test method names are sentences that state the behaviour, e.g. `oneShotUnderflowSetsTheFlagClearsRunAndStaysAtZero`.
- Hung tests are caught by the default in `src/test/resources/junit-platform.properties`. Add a per-test `@Timeout` only when a test needs a different limit.

## Review evidence

- Take build results from the author's report. Run code to confirm a specific doubt.
- Fuzz, seed-sweep or mutation campaigns run when the spec asks for one.
