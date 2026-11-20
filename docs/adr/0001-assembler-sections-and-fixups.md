# Assembler output is Sections plus Fixups, with no linker yet

The Assembler builds a list of Sections with Fixups instead of writing bytes directly, expression values are a `{symbol?, offset}` pair, and every symbol carries a global/local Linkage flag, even though there is no linker and every v1 Section has a fixed origin. This keeps adding a linker later a modest change. The cost is small because Fixups double as the forward-reference mechanism (one pass plus a resolve step instead of two passes).

## Consequences

- Arithmetic on an unresolved value is limited to `sym ± const`, `const + sym` and a leading `<`/`>`; anything else is an error asking to define the symbol first (e.g. `.byte end - start` with `end` defined later). ca65-style expression-tree fixups were rejected to keep a Fixup a plain `{symbol, offset, kind}`.
- v1 symbols are all local; `.export`/`.import` arrive with the linker, so the default never has to flip.
