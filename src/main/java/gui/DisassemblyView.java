package gui;

import cpu.Mode;
import disasm.Disassembler;
import disasm.Disassembler.Code;
import disasm.Disassembler.Instruction;
import disasm.Disassembler.Line;
import disasm.SymbolFile.Symbol;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** The Disassembly pane's logic, with no Swing: a sticky window of lines that moves only when the followed address leaves it. */
final class DisassemblyView {
    /** How far before the followed address a label can be to sweep from when re-anchoring. */
    private static final int REACH = 0x100;

    /**
     * One line as the pane shows it.
     *
     * @param text as {@code d} prints it
     * @param instruction the decoded instruction; null on a label line or a {@code .byte} line
     * @param tooltip a symbol operand's value, else null
     */
    record Row(int addr, String text, boolean label, Instruction instruction, boolean pc, boolean previous, boolean breakpoint, String tooltip) {}

    private Follow follow;
    private boolean following = true;
    private int top, rows = 1;
    /** Where decoding restarts as at a label: the address the window was last anchored on, and bytes scrolled up onto as data. */
    private final Set<Integer> sync = new HashSet<>();
    /** The snapshot shown, null before the first, and the window's lines on it. */
    private Snapshot shown;
    private List<Line> lines = List.of();

    DisassemblyView(String expression) {
        follow = new Follow(expression);
    }

    /** How many lines the window shows. */
    void rows(int n) {
        rows = Math.max(1, n);
        place();
    }

    /** Shows a new snapshot, moving the window while following. */
    void display(Snapshot s) {
        shown = s;
        place();
    }

    /** Following: re-anchors unless the followed address is an instruction start among the lines. Then sweeps the window. */
    private void place() {
        if (shown == null) return;
        lines = window();
        if (!following) return;
        int a = follow.address(shown);
        for (Line l : lines) if (l instanceof Code c && c.addr() == a) return;
        anchor(a);
    }

    /** Sweeps from the nearest label at most {@link #REACH} bytes before {@code a}, synced at {@code a}; with none, {@code a} is the top line. */
    private void anchor(int a) {
        sync.clear();
        sync.add(a);
        int from = -1;
        for (Symbol s : shown.symbols().symbols())
            if (s.label() && s.value() <= a && a - s.value() < REACH && s.value() > from) from = s.value();
        top = from < 0 ? a : from;
        if (from >= 0) {
            List<Line> swept = sweep(from, a + 1); // up to and including a's line
            int at = swept.size() - 1;
            // a's line would fall out of the window under the label: put it a third of the way down instead
            if (at >= rows) top = address(swept.get(at - rows / 3));
        }
        lines = window();
    }

    /** {@code rows} lines from the top line; three bytes a line leaves no line cut short by the end of the sweep. */
    private List<Line> window() {
        List<Line> all = sweep(top, top + Math.min(0x10000, rows * 3 + 3));
        return all.subList(0, Math.min(rows, all.size()));
    }

    private List<Line> sweep(int from, int to) {
        IntUnaryOperator peek = a -> shown.memory()[a & 0xFFFF] & 0xFF;
        var out = new ArrayList<Line>();
        for (Line l : Disassembler.sweep(peek, from, to, shown.symbols().symbols(), sync)) if (!(l instanceof Disassembler.Equate)) out.add(l);
        return out;
    }

    private static int address(Line l) {
        return switch (l) {
            case Disassembler.Label x -> x.addr();
            case Code c -> c.addr();
            case Disassembler.Equate e -> throw new IllegalArgumentException("an equate has no line");
        };
    }

    /** Follows a new expression, attached, moving at once. */
    void follow(String expression) {
        follow = new Follow(expression);
        following(true);
    }

    /** Attached moves with the followed address at once; detached stays where the window is. */
    void following(boolean on) {
        following = on;
        place();
    }

    boolean following() {
        return following;
    }

    String expression() {
        return follow.expression;
    }

    /** Why the expression doesn't resolve now (the window stays at its last good address), or null. */
    String error() {
        return follow.error();
    }

    /** The snapshot shown; null before the first. */
    Snapshot snapshot() {
        return shown;
    }

    /** The top line's address. */
    int top() {
        return top;
    }

    /** Scrolling by hand: {@code address} is the top line, detached. */
    void scroll(int address) {
        following = false;
        top = address & 0xFFFF;
        place();
    }

    /** One line up, detached: the earliest of the three bytes above whose instruction ends on the top line, else the byte above as data. */
    void up() {
        following = false;
        if (shown == null) return;
        IntUnaryOperator peek = a -> shown.memory()[a & 0xFFFF] & 0xFF;
        int above = top - 1;
        for (int c = top - 3; c < top; c++) {
            if (c + Disassembler.decode(peek, c).bytes().length == top) {
                above = c;
                break;
            }
        }
        if (above == top - 1) sync.add(top); // a one-byte .byte line if its decode runs past the top line
        top = above & 0xFFFF;
        place();
    }

    /** One line down, detached: the instruction after the first one is the top line. */
    void down() {
        following = false;
        for (Line l : lines) {
            if (l instanceof Code c) {
                top = c.addr() + c.bytes().length & 0xFFFF;
                break;
            }
        }
        place();
    }

    /** Anchors on {@code address} as following would, detached: Go to target. */
    void goTo(int address) {
        following = false;
        if (shown == null) return;
        anchor(address & 0xFFFF);
    }

    /** The window's lines with their marks, on the snapshot shown. */
    List<Row> lines() {
        var out = new ArrayList<Row>();
        for (Line l : lines) {
            if (l instanceof Disassembler.Label x) out.add(new Row(x.addr(), Disassembler.listing(x), true, null, false, false, false, null));
            if (l instanceof Code c) {
                Instruction in = c.text().startsWith(".byte") ? null : Disassembler.decode(a -> shown.memory()[a & 0xFFFF] & 0xFF, c.addr());
                // a symbol name never holds a $, a hex operand always does
                boolean named = in != null && in.mode().operandSize() > 0 && in.mode() != Mode.IMM && !c.text().contains("$");
                String tooltip = !named ? null : in.mode().operandSize() == 1 && in.mode() != Mode.REL
                        ? String.format("$%02X", in.operand()) : String.format("$%04X", in.operand());
                out.add(new Row(c.addr(), Disassembler.listing(c), false, in, c.addr() == shown.pc(), c.addr() == shown.previousPc(),
                        shown.breakpoints().contains(c.addr()), tooltip));
            }
        }
        return out;
    }

    /** The gutter click at {@code a}: sets a breakpoint there, or deletes the one there. */
    String toggle(int a) {
        return (shown.breakpoints().contains(a) ? "b del " : "b ") + String.format("$%04X", a);
    }

    /** Run to here. */
    static String runTo(int a) {
        return String.format("until $%04X", a);
    }

    /** Set PC here. */
    static String setPc(int a) {
        return String.format("r pc=$%04X", a);
    }

    /** The follow expression Show operand in Memory view gives the current Memory view: the operand's base address, or the pointer for {@code (ptr),Y}; null with no address operand. */
    static String operand(Row r) {
        Instruction in = r.instruction();
        if (in == null || in.mode().operandSize() == 0 || in.mode() == Mode.IMM) return null;
        return String.format(in.mode() == Mode.IZY ? "($%04X)" : "$%04X", in.operand());
    }

    /** Where Go to target goes for a JMP, JSR or branch, or -1. */
    int target(Row r) {
        Instruction in = r.instruction();
        if (in == null) return -1;
        if (in.mode() == Mode.REL || in.mnemonic().equals("JSR") || (in.mnemonic().equals("JMP") && in.mode() == Mode.ABS)) return in.operand();
        if (in.mode() != Mode.IND) return -1;
        int p = in.operand(), hi = (p & 0xFF00) | (p + 1 & 0xFF); // the CPU's page wrap of JMP ($xxFF)
        return (shown.memory()[p] & 0xFF) | (shown.memory()[hi] & 0xFF) << 8;
    }
}
