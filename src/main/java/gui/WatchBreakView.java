package gui;

import java.util.ArrayList;
import java.util.List;
import machine.StopReason;
import machine.Watchpoint;

/** The Watch / Break pane's logic, with no Swing: its rows, which one caused the current Stop, and the commands its actions run. */
final class WatchBreakView {
    private WatchBreakView() {}

    /**
     * One row as the pane shows it.
     *
     * @param address the breakpoint's, or the Watchpoint's start
     * @param hit it caused the current Stop
     */
    record Row(String text, boolean breakpoint, int address, boolean hit) {}

    /** Breakpoints, then Watchpoints, each by address; the current hit is only a stopped snapshot's. */
    static List<Row> rows(Snapshot s) {
        StopReason stop = s.running() ? null : s.stop();
        var out = new ArrayList<Row>();
        for (int a : s.breakpoints()) // until's one-shot address is no breakpoint, so its Stop matches no row
            out.add(new Row(String.format("$%04X", a) + label(s, a), true, a, stop instanceof StopReason.Breakpoint(int pc) && pc == a));
        Watchpoint first = null;
        if (stop instanceof StopReason.Watchpoint(int at, boolean write, _, _, _, _)) {
            for (Watchpoint w : s.watchpoints()) {
                if (w.matches(at, write)) {
                    first = w;
                    break;
                }
            }
        }
        for (Watchpoint w : s.watchpoints())
            out.add(new Row(String.format("%-11s  %s", w.range(), w.kind()) + label(s, w.start()), false, w.start(), w.equals(first)));
        return out;
    }

    private static String label(Snapshot s, int a) {
        String name = s.symbols().label(a);
        return name == null ? "" : "  " + name;
    }

    /** Delete or Remove on a row. */
    static String remove(Row r) {
        return String.format(r.breakpoint() ? "b del $%04X" : "w del $%04X", r.address());
    }
}
