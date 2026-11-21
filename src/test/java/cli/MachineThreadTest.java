package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import gui.MachineThread;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import machine.Device;
import machine.Machine;
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

    /** Assembles {@code body} at $0200, launches it in Program mode and starts the Machine thread (running, or paused). */
    private void start(String body, boolean paused) throws Exception {
        String src = "        *= $0200\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        Files.write(dir.resolve("t.bin"), o.binary());
        Files.writeString(dir.resolve("t.sym"), o.symbols());
        var launch = new Launch(m, null, null, dir.resolve("t.bin"), 0x0200, null, false, List.of());
        var symbols = launch.start();
        thread = new MachineThread(m, (out, resume) -> new Interpreter(m, out, console, symbols, launch, resume, () -> {}));
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
    void queriesAndMemoryWritesAreAnsweredWhileRunningWithoutStopping() throws Exception {
        start(LOOP, false);
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
        for (String c : List.of("go", "continue", "step", "s 2", "next", "n", "load x.bin", "restart", "syms x.sym")) thread.post(c);
        thread.post("help");

        String t = await("help  ");
        for (String c : List.of("go", "continue", "step", "s 2", "next", "n", "load x.bin", "restart", "syms x.sym"))
            assertTrue(t.contains("> " + c + "\nrunning: stop first\n"), c + " in\n" + t);
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
        thread.post("r");

        await("> r\nPC=");
        assertEquals(7, thread.closeCode());
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
    }
}
