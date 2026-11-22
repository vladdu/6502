package cpu;

/**
 * One documented opcode. {@code pageCross}: one extra cycle when the indexed address crosses a page.
 * Branch penalties (taken, taken across a page) are implied by {@link Mode#REL}, not this flag.
 */
public record Opcode(Mnemonic mnemonic, Mode mode, int cycles, boolean pageCross) {}
