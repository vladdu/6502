package asm;

/** A hole at {@code at} in {@code section}, filled with {@code symbol + offset} at resolve. */
record Fixup(Section section, int at, String symbol, int offset, Kind kind, Loc loc) {
    enum Kind { BYTE, WORD, LO, HI, REL8 }
}
