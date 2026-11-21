package gui;

import java.util.Arrays;
import machine.Machine.Area;
import machine.Watchpoint;

/**
 * A Memory view's logic, with no Swing: a window of 16-byte rows over all 64K whose top row holds the followed address while it
 * follows, detached by scrolling by hand; each byte with its page's kind, change highlight and marks; and the commands its actions run.
 */
final class MemoryView {
    /** One byte as the view shows it. */
    record Cell(int value, Area.Kind kind, boolean changed, boolean pc, boolean stack, boolean watched) {}

    private Follow follow;
    private boolean following = true;
    /** The top row's address. */
    private int top;
    /** The snapshot shown, null before the first, and the memory its changed bytes compare with. */
    private Snapshot shown;
    private byte[] before;
    private final Area.Kind[] kinds = new Area.Kind[0x100];

    MemoryView(String expression) {
        follow = new Follow(expression);
    }

    /** Shows a new snapshot: running, changes are against the one shown before; stopped, against the one just before the last run. */
    void display(Snapshot s) {
        before = !s.running() ? s.baselineMemory() : shown == null ? s.memory() : shown.memory();
        shown = s;
        for (Area a : s.areas()) Arrays.fill(kinds, a.base() >> 8, (a.base() + a.size()) >> 8, a.kind());
        place();
    }

    /** While following, the followed address's row goes on top. */
    private void place() {
        if (following && shown != null) top = follow.address(shown) & 0xFFF0;
    }

    /** Follows a new expression, attached, moving at once. */
    void follow(String expression) {
        follow = new Follow(expression);
        following(true);
    }

    /** The Following toggle: on re-attaches at once; off detaches where the view is. */
    void following(boolean on) {
        following = on;
        place();
    }

    /** Scrolling by hand: the row holding {@code address} goes on top, detached. */
    void scroll(int address) {
        following = false;
        top = address & 0xFFF0;
    }

    boolean following() {
        return following;
    }

    /** The top row's address. */
    int top() {
        return top;
    }

    String expression() {
        return follow.expression;
    }

    /** Why the expression doesn't resolve now (the view stays at its last good address), or null. */
    String error() {
        return follow.error();
    }

    /** The snapshot shown; null before the first. */
    Snapshot snapshot() {
        return shown;
    }

    /** The byte at {@code a} in the snapshot shown. */
    Cell cell(int a) {
        boolean watched = false;
        for (Watchpoint w : shown.watchpoints()) watched |= a >= w.start() && a < w.start() + w.length();
        return new Cell(shown.memory()[a] & 0xFF, kinds[a >> 8], shown.memory()[a] != before[a],
                a == shown.pc(), a == 0x0100 + shown.s(), watched);
    }

    /** The ASCII column of the row at {@code row}, as {@code m} shows it. */
    String ascii(int row) {
        var b = new StringBuilder();
        for (int i = row; i < row + 16; i++) {
            int v = shown.memory()[i] & 0xFF;
            b.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
        }
        return b.toString();
    }

    /** A byte's tooltip: its address and its label, if it has one. */
    String tooltip(int a) {
        String label = shown.symbols().label(a);
        return String.format("$%04X", a) + (label == null ? "" : "  " + label);
    }

    /** The command typing {@code typed} over the byte at {@code a} runs, or null when it isn't one or two hex digits. */
    static String edit(int a, String typed) {
        String t = typed.trim();
        return t.matches("[0-9A-Fa-f]{1,2}") ? String.format("m $%04X = $%02X", a, Integer.parseInt(t, 16)) : null;
    }

    /** The command watching {@code [first, last]} for reads, writes or both. */
    static String watch(boolean reads, boolean writes, int first, int last) {
        return (reads ? writes ? "w rw" : "w r" : "w") + String.format(" $%04X $%X", first, last - first + 1);
    }
}
