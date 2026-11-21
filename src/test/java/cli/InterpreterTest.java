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
}
