package asm;

/** A hole at {@code at} in its Section, filled with {@code symbol + offset} at resolve. */
record Fixup(int at, String symbol, int offset, Kind kind, Loc loc) {
    enum Kind { BYTE, WORD, LO, HI, REL8 }
}
