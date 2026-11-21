package gui;

import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import machine.StopReason;
import machine.Watchpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Watch / Break pane's logic: its rows, the current hit and the commands its actions run. */
class WatchBreakViewTest {
    @TempDir Path dir;
    private final SnapshotBuilder b = new SnapshotBuilder();

    private static List<String> texts(Snapshot s) {
        return WatchBreakView.rows(s).stream().map(WatchBreakView.Row::text).toList();
    }

    private static List<String> hits(Snapshot s) {
        return WatchBreakView.rows(s).stream().filter(WatchBreakView.Row::hit).map(WatchBreakView.Row::text).toList();
    }

    @Test
    void breakpointsComeFirstThenWatchpointsEachByAddressWithTheirLabels() throws Exception {
        var t = new SymbolTable();
        t.add(Files.writeString(dir.resolve("t.sym"), "0812 loop label global\n0300 msg label global\n"));
        b.symbols = t;
        b.breakpoints = List.of(0x0200, 0x0812);
        b.watchpoints = List.of(new Watchpoint(0x0300, 4, false, true), new Watchpoint(0x0400, 1, true, false),
                new Watchpoint(0x0500, 2, true, true));

        assertEquals(List.of("$0200", "$0812  loop", "$0300-$0303  w  msg", "$0400        r", "$0500-$0501  rw"), texts(b.build()));
    }

    @Test
    void aBreakpointStopHighlightsItsRowUntilTheNextRun() {
        b.breakpoints = List.of(0x0200, 0x0812);
        b.stop = new StopReason.Breakpoint(0x0812);
        assertEquals(List.of("$0812"), hits(b.build()));
        b.running = true;
        assertEquals(List.of(), hits(b.build()));
    }

    @Test
    void untilsOneShotStopHighlightsNothing() {
        b.breakpoints = List.of(0x0200);
        b.stop = new StopReason.Breakpoint(0x0300);
        assertEquals(List.of(), hits(b.build()));
    }

    @Test
    void aWatchpointStopHighlightsOnlyTheFirstHitsRow() {
        b.watchpoints = List.of(new Watchpoint(0x0300, 4, true, false), new Watchpoint(0x0302, 2, false, true),
                new Watchpoint(0x0303, 1, false, true));
        b.stop = new StopReason.Watchpoint(0x0303, true, 0, 1, "$0200", 1);
        assertEquals(List.of("$0302-$0303  w"), hits(b.build()));
    }

    @Test
    void otherStopsAndPausedHighlightNothing() {
        b.breakpoints = List.of(0x0200);
        b.watchpoints = List.of(new Watchpoint(0x0200, 1, true, true));
        assertEquals(List.of(), hits(b.build()));
        b.stop = new StopReason.UserBreak();
        assertEquals(List.of(), hits(b.build()));
    }

    @Test
    void removingARowDeletesItsBreakpointOrWatchpoint() {
        b.breakpoints = List.of(0x0812);
        b.watchpoints = List.of(new Watchpoint(0x0300, 4, false, true));
        List<WatchBreakView.Row> rows = WatchBreakView.rows(b.build());

        assertEquals("b del $0812", WatchBreakView.remove(rows.get(0)));
        assertEquals("w del $0300", WatchBreakView.remove(rows.get(1)));
    }
}
