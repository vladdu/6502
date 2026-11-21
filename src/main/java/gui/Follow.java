package gui;

import cli.Interpreter;

/** A follow expression (a Monitor value, {@code pc}, {@code s} or {@code (EXPR)}) re-evaluated per snapshot, keeping its last good address. */
final class Follow {
    final String expression;
    /** The last good address, $0000 before the first. */
    private int address;
    /** Why the last evaluation failed; null when it resolved. */
    private String error;

    Follow(String expression) {
        this.expression = expression.trim();
    }

    /** The address on {@code s}, or the last good one when the expression doesn't resolve there. */
    int address(Snapshot s) {
        try {
            address = evaluate(expression, s);
            error = null;
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        return address;
    }

    /** Why the last {@link #address} call kept the last good address; null when it resolved. */
    String error() {
        return error;
    }

    private static int evaluate(String e, Snapshot s) {
        if (e.startsWith("(") && e.endsWith(")")) {
            int a = evaluate(e.substring(1, e.length() - 1).trim(), s);
            return (s.memory()[a] & 0xFF) | (s.memory()[a + 1 & 0xFFFF] & 0xFF) << 8;
        }
        if (e.equals("pc")) return s.pc();
        if (e.equals("s")) return 0x0100 + s.s();
        int v = Interpreter.value(e, s.symbols());
        if (v >>> 16 != 0) throw new IllegalArgumentException(String.format("%s: $%X is not an address", e, v));
        return v;
    }
}
