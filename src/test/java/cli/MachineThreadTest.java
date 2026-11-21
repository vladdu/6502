package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import gui.MachineThread;
import gui.Snapshot;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import machine.Device;
import machine.Machine;
import machine.Pins;
import machine.StopReason;
import machine.Watchpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The Front panel's Machine thread with no Swing: posted lines in, transcript text and exit codes out. */
class MachineThreadTest {
    private static final String LOOP = "loop:   INC $10\n        JMP loop";
    private final Monitor.ConsoleOut console = new Monitor.ConsoleOut(new ByteArrayOutputStream());
    private final Machine m = new Machine(new LinkedBlockingQueue<>(), console);
    @TempDir Path dir;
    private MachineThread thread;
    private final StringBuilder transcript = new StringBuilder();

    /** Assembles {@code body} at $0200, launches it in Program mode and starts the Machine thread (running, or paused) with {@code queued} already posted. */
    private void start(String body, boolean paused, String... queued) throws Exception {
        String src = "        *= $0200\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        Files.write(dir.resolve("t.bin"), o.binary());
        Files.writeString(dir.resolve("t.sym"), o.symbols());
        var launch = new Launch(m, null, null, dir.resolve("t.bin"), 0x0200, null, false, List.of());
        var symbols = launch.start();
        thread = new MachineThread(m, (out, resume) -> new Interpreter(m, out, console, symbols, launch, resume, () -> {}));
        for (String line : queued) thread.post(line);
        thread.start(paused, null);
    }

    /** The transcript so far, once it contains {@code text}; the default test timeout fails a wait that never ends. */
    private String await(String text) throws InterruptedException {
        while (!transcript.toString().contains(text)) {
            transcript.append(thread.output());
            Thread.sleep(1);
        }
        return transcript.toString();
    }

    /** The published snapshot, once {@code wanted} holds for it. */
    private Snapshot snapshot(Predicate<Snapshot> wanted) throws InterruptedException {
        for (Snapshot s; ; Thread.sleep(1)) if ((s = thread.snapshot()) != null && wanted.test(s)) return s;
    }

    @AfterEach
    void quit() {
        if (thread != null) thread.post("quit");
    }

    @Test
    void aPausedStartShowsPausedAndRunsPostedCommandsEchoed() throws Exception {
        start(LOOP, true);
        thread.post("r a=5");

        String t = await("A=05");
        assertTrue(t.startsWith("Paused\nPC=0200"), t);
        assertTrue(t.contains("> r a=5\nPC=0200 A=05"), t);
    }

    @Test
    void aBreakpointSetWhileRunningIsAppliedBetweenStepsAndStopsTheRun() throws Exception {
        start(LOOP, false);
        thread.post("b loop");

        await("Breakpoint at $0200 (loop)");
    }

    @Test
    void aBreakpointSetWhileUntilRunsStopsTheRun() throws Exception {
        start(LOOP, true);
        thread.post("until 0300");
        thread.post("b 0202");

        await("Breakpoint at $0202");
    }

    @Test
    void aBreakpointSetWhileNextRunsOverAJsrStopsTheRun() throws Exception {
        start("        JSR sub\n        BRK\nsub:    INC $10\n        JMP sub", true);
        thread.post("next");
        thread.post("b 0206");

        await("Breakpoint at $0206");
    }

    @Test
    void queriesAndMemoryWritesAreAnsweredWhileRunningWithoutStopping() throws Exception {
        start(LOOP, false);
        snapshot(Snapshot::running); // posted while running, not queued before the run
        thread.post("m 0300 = 42");
        thread.post("m 0300 1");
        thread.post("r");
        thread.post("info");
        thread.post("go");

        String t = await("> go\nrunning: stop first");
        assertTrue(t.contains("0300  42"), t);
        assertTrue(t.contains("> r\nPC="), t);
        assertTrue(t.contains("Speed: unthrottled"), t);
    }

    @Test
    @Timeout(2)
    void aThrottledMachineAnswersAPostedCommandAtOnce() throws Exception {
        m.hz = 1; // each Step is due seconds later
        start(LOOP, false);
        thread.post("r");

        await("> r\nPC=");
    }

    @Test
    void aWatchpointSetWhileRunningIsAppliedBetweenStepsAndStopsTheRun() throws Exception {
        start(LOOP, false);
        thread.post("w 10");

        String t = await("Watchpoint: write $0010");
        assertFalse(t.contains("running: stop first"), t);
    }

    @Test
    void nmiWhileRunningPressesTheButtonAndTheRunTakesTheNmiEntry() throws Exception {
        start(LOOP + "\n        *= $0300\nhandler: JMP handler", false);
        thread.post("m FFFA 2 = 00 03");
        thread.post("b handler");
        thread.post("nmi");

        String t = await("Breakpoint at $0300 (handler)");
        assertFalse(t.contains("running: stop first"), t);
    }

    @Test
    void runControlIsRefusedWhileRunning() throws Exception {
        start(LOOP, false);
        snapshot(Snapshot::running); // posted while running, not queued before the run
        for (String c : List.of("go", "continue", "step", "s 2", "next", "n", "load x.bin", "restart", "syms x.sym")) thread.post(c);
        thread.post("help");

        String t = await("help  ");
        for (String c : List.of("go", "continue", "step", "s 2", "next", "n", "load x.bin", "restart", "syms x.sym"))
            assertTrue(t.contains("> " + c + "\nrunning: stop first\n"), c + " in\n" + t);
    }

    @Test
    void runControlQueuedBeforeARunWaitsForItsStopInsteadOfBeingRefused() throws Exception {
        start(LOOP, true, "step", "next", "", "r");

        String t = await("> r\nPC=");
        assertTrue(t.contains("> step\nStep\n"), t);
        assertTrue(t.contains("> next\nStep\n"), t);
        assertFalse(t.contains("running: stop first"), t);
        assertEquals(3, thread.snapshot().steps()); // the empty line repeated next
    }

    @Test
    void anUnknownCommandWhileRunningIsReportedAsUnknown() throws Exception {
        start(LOOP, false);
        thread.post("foo");

        await("> foo\nunknown command 'foo'");
    }

    @Test
    void aBreakStopsARunAndEveryCommandIsAvailableAgain() throws Exception {
        start(LOOP, false);
        thread.post("r");
        await("> r\nPC="); // answered between Steps, so running
        thread.requestBreak();
        await("User break");
        thread.post("step");

        await("> step\nStep\n");
    }

    @Test
    void resetWhileRunningKeepsRunningFromTheResetVector() throws Exception {
        start(LOOP + "\n        *= $0300\nother:  JMP other", false);
        snapshot(Snapshot::running); // posted while running, not queued before the run
        thread.post("m FFFC 2 = 00 03");
        thread.post("reset");
        thread.post("go");
        thread.post("b other");

        String t = await("Breakpoint at $0300 (other)");
        assertTrue(t.contains("> go\nrunning: stop first"), t);
    }

    @Test
    void anExitIsAnOrdinaryStopWhoseCodeIsTheCloseCode() throws Exception {
        start("        LDA #7\n        STA $D300", false);
        await("Exited with code 7\nPC=");
        Snapshot s = snapshot(x -> !x.running());
        assertEquals(new StopReason.Exited(7), s.stop());
        assertEquals("Exited with code 7", s.stopText());
        thread.post("r");

        await("> r\nPC=");
        assertEquals(7, thread.closeCode());
    }

    @Test
    void resetOrRestartAfterAnExitMakesTheCloseCodeZero() throws Exception {
        start("        LDA #7\n        STA $D300", false);
        snapshot(x -> x.stop() instanceof StopReason.Exited);
        thread.post("reset");
        assertNull(snapshot(x -> x.stopText().equals("Reset")).stop());
        assertEquals(0, thread.closeCode());
        thread.post("go 0200");
        snapshot(x -> x.stop() instanceof StopReason.Exited);
        thread.post("restart");

        assertNull(snapshot(x -> x.stopText().equals("Restart")).stop());
        assertEquals(0, thread.closeCode());
    }

    @Test
    void quitWhileRunningEndsTheSessionWithItsCode() throws Exception {
        start(LOOP, false);
        thread.post("quit 3");

        assertEquals(3, thread.exit.get());
    }

    @Test
    void closingWhileRunningGivesZero() throws Exception {
        start(LOOP, false);
        thread.close();

        assertEquals(0, thread.exit.get());
    }

    @Test
    void anExceptionOnTheMachineThreadStopsTheMachineAsABugAndCommandsStayAvailable() throws Exception {
        m.map(0xD400, 0x100, new Device() {
            @Override
            public int read(int off) {
                return 0;
            }

            @Override
            public void write(int off, int v) {
                throw new IllegalStateException("broken Device");
            }

            @Override
            public int peek(int off) {
                return 0;
            }
        });
        start("        STA $D400", false);
        await("bug: java.lang.IllegalStateException: broken Device");
        thread.post("r");

        await("> r\nPC=");
        assertEquals(125, thread.closeCode());
        snapshot(x -> !x.running() && x.stopText().equals("Machine thread bug"));
    }

    @Test
    void aBugStopClearsTheCurrentStop() throws Exception {
        m.map(0xD400, 0x100, new Device() {
            @Override
            public int read(int off) {
                return 0;
            }

            @Override
            public void write(int off, int v) {
                throw new IllegalStateException("broken Device");
            }

            @Override
            public int peek(int off) {
                return 0;
            }
        });
        start(LOOP, true, "b 0202", "go");
        await("Breakpoint at $0202");
        thread.post("m D400 = 1");

        assertNull(snapshot(x -> x.stopText().equals("Machine thread bug")).stop());
    }

    @Test
    void anExceptionBuildingASnapshotIsABugStopAndCommandsStayAvailable() throws Exception {
        m.map(0xD400, 0x100, new Device() {
            @Override
            public int read(int off) {
                return 0;
            }

            @Override
            public void write(int off, int v) {}

            @Override
            public int peek(int off) {
                throw new IllegalStateException("broken peek");
            }
        });
        start(LOOP, true);
        await("bug: java.lang.IllegalStateException: broken peek");
        thread.post("r");

        await("> r\nPC=");
        assertEquals(125, thread.closeCode());
    }

    @Test
    void aSnapshotIsPublishedAtAStopAndMatchesTheMachine() throws Exception {
        start(LOOP, true);
        thread.post("b 0202");
        thread.post("w 0300 2");
        thread.post("go");

        Snapshot s = snapshot(x -> x.stop() != null && !x.running());
        assertEquals(new StopReason.Breakpoint(0x0202), s.stop());
        assertEquals("Breakpoint at $0202", s.stopText());
        assertEquals(0x0202, s.pc());
        assertEquals(0, s.a()); assertEquals(0, s.x()); assertEquals(0, s.y());
        assertEquals(0xFD, s.s()); assertEquals(0x24, s.p());
        assertEquals(5, s.cycles()); // one INC zp
        assertEquals(1, s.steps());
        assertEquals(new Pins(List.of(), List.of()), s.pins());
        assertEquals(m.states(), s.devices());
        assertEquals(List.of(0x0202), s.breakpoints());
        assertEquals(List.of(new Watchpoint(0x0300, 2, false, true)), s.watchpoints());
        assertEquals(1, s.memory()[0x10]);
        assertEquals((byte) 0xE6, s.memory()[0x0200]); // INC zp
        assertEquals((byte) 0xFF, s.memory()[0xD400]); // unmapped
        assertEquals(0x10000, s.memory().length);
        assertEquals("loop", s.symbols().label(0x0200));
    }

    @Test
    void aSnapshotIsPublishedAfterEveryCommand() throws Exception {
        start(LOOP, true);
        assertEquals("Paused", snapshot(x -> true).stopText());
        thread.post("r a=5");
        snapshot(x -> x.a() == 5);
        thread.post("m 0300 = 42");

        Snapshot s = snapshot(x -> x.memory()[0x0300] == 0x42);
        assertFalse(s.running());
        assertNull(s.stop()); // Paused
    }

    @Test
    void aRunningMachineAnswersARefreshRequestWithOneConsistentState() throws Exception {
        start(LOOP, false);
        long first = snapshot(Snapshot::running).id();
        thread.requestSnapshot();

        Snapshot s = snapshot(x -> x.id() > first);
        assertTrue(s.running());
        long incs = (s.steps() + 1) / 2; // the loop alternates INC $10 (5 cycles) and JMP (3 cycles)
        assertEquals(incs * 5 + s.steps() / 2 * 3, s.cycles());
        assertEquals((byte) incs, s.memory()[0x10]);
        assertEquals(s.steps() % 2 == 0 ? 0x0200 : 0x0202, s.pc());
    }

    @Test
    @Timeout(2)
    void aThrottledMachineAnswersARefreshRequestAtOnce() throws Exception {
        m.hz = 1; // each Step is due seconds later
        start(LOOP, false);
        long first = snapshot(Snapshot::running).id();
        thread.requestSnapshot();

        snapshot(x -> x.id() > first && x.running());
    }

    @Test
    void aCommandWhileRunningIsFollowedByARunningSnapshotWithItsChange() throws Exception {
        start(LOOP, false);
        thread.post("m 0300 = 42");

        assertTrue(snapshot(x -> x.memory()[0x0300] == 0x42).running());
    }

    @Test
    void stoppedSnapshotsKeepTheBaselineFromJustBeforeTheLastRun() throws Exception {
        start(LOOP, true);
        thread.post("m 0300 = 1");
        thread.post("step");
        Snapshot stepped = snapshot(x -> x.steps() == 1 && !x.running());
        thread.post("m 0300 = 2");

        Snapshot s = snapshot(x -> x.memory()[0x0300] == 2);
        assertEquals(stepped.baseline(), s.baseline());
        assertTrue(s.baseline() < stepped.id());
        assertEquals(0, s.baselineMemory()[0x10]);
        assertEquals(1, s.memory()[0x10]);
        assertEquals(1, s.baselineMemory()[0x0300]); // the m = edit after the run counts as a change
    }

    @Test
    void beforeAnyRunTheFirstSnapshotIsTheBaselineSoMemoryEditsAreChanges() throws Exception {
        start(LOOP, true);
        thread.post("m 0300 = 1");

        Snapshot s = snapshot(x -> x.memory()[0x0300] == 1);
        assertEquals(0, s.baselineMemory()[0x0300]);
        assertEquals(m.areas(), s.areas());
    }

    @Test
    void theSymbolTableCopyIsRetakenOnlyWhenItChanges() throws Exception {
        start(LOOP, true);
        Snapshot before = snapshot(x -> true);
        thread.post("r a=1");
        assertSame(before.symbols(), snapshot(x -> x.a() == 1).symbols());
        Path more = dir.resolve("more.sym");
        Files.writeString(more, "0300 other label global\n");
        thread.post("syms " + more);

        Snapshot s = snapshot(x -> x.symbols().value("other") != null);
        assertNotSame(before.symbols(), s.symbols());
        assertEquals("loop", s.symbols().label(0x0200));
        assertNull(before.symbols().value("other"));
    }
}
