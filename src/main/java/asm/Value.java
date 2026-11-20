package asm;

/** An expression value: {@code symbol + offset}, with {@code symbol} null once resolved. */
record Value(String symbol, int offset) {
    static Value of(int v) {
        return new Value(null, v);
    }

    boolean resolved() {
        return symbol == null;
    }
}
