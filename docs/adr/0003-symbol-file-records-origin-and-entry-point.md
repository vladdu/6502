# The Symbol file records an image's Image origin and Entry point

Images stay raw, because ROMs, `.incbin` and foreign binaries need plain bytes, so the Assembler records the Image origin, the Entry point and a checksum of the image in the Symbol file. We rejected a header, which needs a second format, and a third output file, which can go stale in the same way. This replaces "the load address comes from the CLI".

## Consequences

- The Symbol file is no longer only for debugging: it decides how a program starts. One that doesn't match its image is refused unless an address is typed or, for the Disassembler, the file is named.
- A typed address wins, and the Entry point moves with the image. Symbols don't move.
- An image without its Symbol file loads and starts at $0200, as any raw binary does.
- The checksum can't see a change of Entry point alone, so the Symbol file is written before the image.
