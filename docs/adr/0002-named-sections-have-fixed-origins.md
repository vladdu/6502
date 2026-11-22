# Named Sections have fixed origins and need no linker

The program declares every Section with an origin, and files continue it by name. Addresses stay known in the one pass of ADR 0001, so we accept hand-picked origins with an overlap check, where floating Sections would need relocation.

## Consequences

- `*=` is gone: a second way to move the PC allowed silent aliasing and variables that no overlap check could see.
- A forward reference that turns out to be in zero page is an error rather than a resize, so an instruction's size depends only on the source above it.
- Floating origins and load addresses different from run addresses each need a new decision.
