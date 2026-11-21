package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import machine.Machine;
import machine.StopReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command interpreter driven by lines with no Terminal; asserts printed text, results and Machine state. */
class InterpreterTest {
    private final StringWriter out = new StringWriter();
    private final Monitor.ConsoleOut consoleOut = new Monitor.ConsoleOut(new ByteArrayOutputStream());
    private final Machine m = new Machine(new LinkedBlockingQueue<>(), consoleOut);
    private final List<String> hooks = new ArrayList<>();
    @TempDir Path launchDir;
    private Interpreter interpreter;

    /** Assembles {@code body} at $0200 and launches it in Program mode, then builds the interpreter over it. */
    private void load(String body) throws Exception {
        String src = "        *= $0200\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        Files.write(launchDir.resolve("t.bin"), o.binary());
        Files.writeString(launchDir.resolve("t.sym"), o.symbols());
        var launch = new Launch(m, null, null, launchDir.resolve("t.bin"), 0x0200, null, false, List.of());
        interpreter = new Interpreter(m, new PrintWriter(out), consoleOut, launch.start(), launch, () -> hooks.add("resume"), () -> hooks.add("pause"));
    }

    @Test
    void aLineRunsItsCommandAndPrintsToTheWriter() throws Exception {
        load("NOP");

        assertNull(interpreter.execute("r a=5"));

        assertEquals("PC=0200 A=05 X=00 Y=00 S=FD P=nv-bdIzc  cyc=0\n", out.toString());
        assertEquals(5, m.cpu.a);
    }

    @Test
    void aMistakeIsPrintedAndTheNextLineStillRuns() throws Exception {
        load("NOP");

        assertNull(interpreter.execute("foo"));
        interpreter.execute("r x=1");

        assertTrue(out.toString().startsWith("unknown command 'foo' (help lists them)\n"), out.toString());
        assertEquals(1, m.cpu.x);
    }

    @Test
    void quitComesBackAsAResultWithItsCode() throws Exception {
        load("NOP");

        assertEquals(new Interpreter.Quit(42), interpreter.execute("quit 2a"));
        assertEquals(new Interpreter.Quit(0), interpreter.execute("q"));
    }

    @Test
    void anExitComesBackAsTheStopOfTheRunOrTheWrite() throws Exception {
        load("""
                        BRK
                exit:   LDA #42
                        STA $D300
                """);

        assertEquals(new Interpreter.Stopped(new StopReason.Exited(42)), interpreter.execute("go exit"));
        load("NOP");
        assertEquals(new Interpreter.Stopped(new StopReason.Exited(7)), interpreter.execute("m D300 = 7"));
    }

    @Test
    void showGivesTheStopDisplayAndAStepReturnsItsStopAndABlankLineRepeatsIt() throws Exception {
        load("NOP\nNOP");

        interpreter.show(null);
        var r = interpreter.execute("s");
        interpreter.execute("");

        assertEquals(new Interpreter.Stopped(new StopReason.Stepped(null, 0x0201)), r);
        assertEquals(0x0202, m.cpu.pc);
        assertEquals("""
                Paused
                PC=0200 A=00 X=00 Y=00 S=FD P=nv-bdIzc  cyc=0
                0200  EA        NOP
                Step
                PC=0201 A=00 X=00 Y=00 S=FD P=nv-bdIzc  cyc=2
                0201  EA        NOP
                Step
                PC=0202 A=00 X=00 Y=00 S=FD P=nv-bdIzc  cyc=4
                0202  00        BRK
                """, out.toString());
    }

    @Test
    void onlyStepExecutingCommandsCallTheFrontEndHooksAroundTheirRun() throws Exception {
        load("NOP\nNOP\nloop: JMP loop");

        for (String line : List.of("r", "m 200 1", "b loop", "s", "n", "go", "reset")) interpreter.execute(line);

        assertEquals(List.of("resume", "pause", "resume", "pause", "resume", "pause"), hooks);
    }

    @Test
    void wAddsReplacesAtTheSameStartListsAndDeletesWatchpoints() throws Exception {
        load("""
                        NOP
                buf:    .byte 0
                """);

        for (String line : List.of("w", "w buf", "w r 300 4", "w rw 280 #16", "w", "w buf 2", "w del 300", "w del 300", "w"))
            interpreter.execute(line);

        assertEquals("""
                no watchpoints
                $0201 w (buf)
                $0280-$028F rw
                $0300-$0303 r
                no watchpoint at $0300
                $0201-$0202 w (buf)
                $0280-$028F rw
                """, out.toString());
    }

    @Test
    void wRefusesBadSyntaxAndRangesPastTheTopOfMemory() throws Exception {
        load("NOP");

        for (String line : List.of("w x 300", "w r", "w 300 4 5", "w del", "w FFFF 2", "w 300 0", "w 10000"))
            interpreter.execute(line);

        assertEquals("""
                'x' is not a number or symbol
                usage: w [r|rw] ADDR [LEN] | w del ADDR
                usage: w [r|rw] ADDR [LEN] | w del ADDR
                usage: w [r|rw] ADDR [LEN] | w del ADDR
                2: count must be 1 to $1
                0: count must be 1 to $FD00
                10000: $10000 is not an address
                """, out.toString());
    }

    @Test
    void aWatchpointStopsGoNextAndStepAndSurvivesRestart() throws Exception {
        load("""
                start:  LDA #$13
                        STA $0300
                        JSR sub
                        BRK
                sub:    STA $0300
                        RTS
                """);
        interpreter.execute("w 300");

        var r = interpreter.execute("go");
        interpreter.execute("next");
        interpreter.execute("restart");
        interpreter.execute("step 5");

        assertEquals(new Interpreter.Stopped(new StopReason.Watchpoint(0x0300, true, 0x00, 0x13, "$0202", 0)), r);
        assertEquals("""
                Watchpoint: write $0300 $00->$13 by $0202
                PC=0205 A=13 X=00 Y=00 S=FD P=nv-bdIzc  cyc=6
                0205  20 09 02  JSR sub
                Watchpoint: write $0300 $13->$13 by $0209
                PC=020C A=13 X=00 Y=00 S=FB P=nv-bdIzc  cyc=16
                020C  60        RTS
                Restart
                PC=0200 A=00 X=00 Y=00 S=FD P=nv-bdIzc  cyc=0
                0200  A9 13     LDA #$13
                Watchpoint: write $0300 $00->$13 by $0202
                PC=0205 A=13 X=00 Y=00 S=FD P=nv-bdIzc  cyc=6
                0205  20 09 02  JSR sub
                """, out.toString());
    }

    @Test
    void helpListsW() throws Exception {
        load("NOP");

        interpreter.execute("help");

        assertTrue(out.toString().contains("w [r|rw] ADDR [LEN]"), out.toString());
    }
}
