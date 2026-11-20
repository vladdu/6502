package asm;

import asm.Fixup.Kind;

/**
 * An expression value: {@code symbol + offset}, with {@code symbol} null once resolved. {@code part}
 * is LO or HI for an unresolved value under a leading {@code <} or {@code >}, else null. {@code address}
 * marks a resolved value derived from a label or {@code *}, which is not a constant to add to an
 * unresolved symbol.
 */
record Value(String symbol, int offset, Kind part, boolean address) {
    Value(String symbol, int offset) {
        this(symbol, offset, null, false);
    }

    Value(String symbol, int offset, boolean address) {
        this(symbol, offset, null, address);
    }

    static Value of(int v) {
        return new Value(null, v);
    }

    boolean resolved() {
        return symbol == null;
    }
}
