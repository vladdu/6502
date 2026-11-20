package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import disasm.SymbolFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import machine.Machine;
import machine.StopReason;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The Monitor driven by lines over a DumbTerminal; asserts printed text and Machine state. */
class MonitorTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<Integer> consoleIn = new LinkedBlockingQueue<>();
    private final Monitor.ConsoleOut consoleOut = new Monitor.ConsoleOut(out);
    private final Machine m = new Machine(consoleIn, consoleOut);
    private List<SymbolFile.Symbol> symbols = List.of();
    private int exitCode = -1;

    /** Assembles {@code body} at $0200, loads it in Program mode and keeps its symbols. */
    private void load(String body) throws Exception {
        String src = "        *= $0200\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        m.loadProgram(o.binary(), 0x0200);
        symbols = SymbolFile.parse("t.sym", o.symbols());
    }

    /** Enters the Monitor with {@code reason} and feeds it {@code lines}; input EOF is Ctrl-D. Returns what it printed. */
    private String monitor(StopReason reason, String lines) throws Exception {
        Terminal t = new DumbTerminal("test", Terminal.TYPE_DUMB, new ByteArrayInputStream(lines.getBytes(UTF_8)), out, UTF_8);
        t.enterRawMode(); // as the HostConsole leaves it
        exitCode = new Monitor(m, t, consoleOut, symbols, () -> {}, () -> {}).session(reason);
        t.close();
        return out.toString(UTF_8);
    }

    @Test
    void stopDisplayShowsReasonRegistersCyclesAndTheCurrentInstruction() throws Exception {
        load("""
                        LDX #5
                loop:   DEX
                        BNE loop
                """);
        StopReason r = m.run(Set.of(0x0202));

        String text = monitor(r, "");

        assertTrue(text.contains("""
                Breakpoint at $0202 (loop)
                PC=0202 A=00 X=05 Y=00 S=FD P=nv-bdIzc  cyc=2
                0202  CA        DEX
                """), text);
        assertEquals(0, exitCode); // Ctrl-D quits
    }

    @Test
    void rSetsRegistersAndSingleFlagsIgnoringBAndBit5ThenShows() throws Exception {
        load("NOP");

        String text = monitor(null, "r pc=0300 a=ff x=#10 s=fd p=ff c=0 b=0\nr\n");

        assertEquals(0x0300, m.cpu.pc);
        assertEquals(0xFF, m.cpu.a);
        assertEquals(10, m.cpu.x);
        assertEquals(0xEE, m.cpu.p); // B never stored, bit 5 always set, then C cleared
        String regs = "PC=0300 A=FF X=0A Y=00 S=FD P=NV-bDIZc  cyc=0\n";
        assertEquals(2, text.split(java.util.regex.Pattern.quote(regs), -1).length - 1, text); // after the set, and by r
    }

    @Test
    void rWithABadAssignmentChangesNothing() throws Exception {
        load("NOP");

        String text = monitor(null, "r a=12 q=1\nr a=100\n");

        assertEquals(0, m.cpu.a);
        assertTrue(text.contains("unknown register or flag 'q'"), text);
        assertTrue(text.contains("a=100: value $100 is out of range"), text);
    }

    @Test
    void breakpointsTakeSymbolsWithOffsetsAndListByAddress() throws Exception {
        load("""
                start:  LDX #1
                loop:   INX
                        JMP loop
                """);

        String text = monitor(null, "b loop+1\nb start\nb $10\nb nosuch\nb\nb del loop+#1\nb\nb del 300\n");

        assertTrue(text.contains("'nosuch' is not a number or symbol"), text);
        assertTrue(text.contains("> $0010\n$0200 (start)\n$0203\n> > $0010\n$0200 (start)\n> no breakpoint at $0300\n"), text);
    }

    @Test
    void goDoesNotStopOnABreakpointAtTheStartPcForItsFirstStep() throws Exception {
        load("""
                loop:   INX
                        JMP loop
                """);

        String text = monitor(null, "b loop\ngo\n");

        assertTrue(text.contains("""
                Breakpoint at $0200 (loop)
                PC=0200 A=00 X=01 Y=00 S=FD P=nv-bdIzc  cyc=5
                """), text);
    }

    @Test
    void goAddrSetsPcFirstAndAnExitEndsTheSessionWithItsCode() throws Exception {
        load("""
                        BRK
                exit:   LDA #42
                        STA $D300
                """);

        monitor(null, "go exit\nr\n");

        assertEquals(42, exitCode);
    }

    @Test
    void stepOverBrkShowsItsInterruptEntry() throws Exception {
        load("BRK");
        m.bus.write(0xFFFE, 0x00); // BRK/IRQ vector $E100
        m.bus.write(0xFFFF, 0xE1);
        m.bus.write(0xE100, 0x40); // RTI

        String text = monitor(null, "s\n");

        assertTrue(text.contains("> BRK entry -> $E100\nPC=E100 "), text);
    }

    @Test
    void stepShowsAnIrqEntryAsAStepAndAnEmptyLineRepeatsOnlyStep() throws Exception {
        load("""
                        LDA #$80
                        STA $D002   ; Console CONTROL: receive IRQ on
                        CLI
                        NOP
                """);
        m.bus.write(0xFFFE, 0x00); // IRQ vector $E100
        m.bus.write(0xFFFF, 0xE1);
        m.bus.write(0xE100, 0x40); // RTI
        consoleIn.add((int) 'x'); // a byte waiting: the Console asserts IRQ once enabled

        String text = monitor(null, "step 2\ns\n\nr\n\n");

        assertTrue(text.contains("""
                Step
                PC=0205 A=80 X=00 Y=00 S=FD P=Nv-bdIzc  cyc=6
                0205  58        CLI
                """), text);
        assertTrue(text.contains("""
                IRQ entry -> $E100
                PC=E100 A=80 X=00 Y=00 S=FA P=Nv-bdIzc  cyc=15
                E100  40        RTI
                """), text);
        assertEquals(0xE100, m.cpu.pc); // the empty line after r repeated nothing
        assertEquals(15, m.cycles);
    }

    @Test
    void stepIntoAnUndocumentedOpcodeShowsIllegalAndAnExitDuringAStepEndsTheSession() throws Exception {
        load("""
                        .byte $DB
                        LDA #3
                        STA $D300
                """);

        String text = monitor(null, "s\nr pc=201\ns 5\n");

        assertTrue(text.contains("Illegal opcode $DB at $0200\nPC=0200"), text);
        assertEquals(3, exitCode);
    }

    @Test
    void quitExitsWithItsCode() throws Exception {
        load("NOP");
        monitor(null, "quit 2a\n");
        assertEquals(42, exitCode);
        monitor(null, "q #7\n");
        assertEquals(7, exitCode);
        monitor(null, "q\n");
        assertEquals(0, exitCode);
    }

    @Test
    void ctrlCCancelsTheLineAndCtrlRightBracketIsIgnored() throws Exception {
        load("NOP");

        monitor(null, "r a=5\u0003q\u001d 3\n");

        assertEquals(0, m.cpu.a);
        assertEquals(3, exitCode);
    }

    @Test
    void helpListsTheCommands() throws Exception {
        load("NOP");

        String text = monitor(null, "help\nfoo\n");

        for (String c : List.of("go", "continue", "step", "b ADDR", "b del", "r", "quit", "help")) assertTrue(text.contains(c), c);
        assertTrue(text.contains("unknown command 'foo' (help lists them)"), text);
    }

    @Test
    void theStopDisplayStartsOnAFreshLineAfterConsoleOutput() throws Exception {
        load("""
                        LDA #'A'
                        STA $D000
                        LDA #10
                        STA $D000
                stop:   NOP
                """);

        String text = monitor(null, "step 2\nstep 2\n");

        assertTrue(text.contains("> A\nStep\n"), text);
        assertTrue(text.contains("> \nStep\n"), text); // after LF, no extra newline
    }

    @Test
    void rRejectsNegativeValues() throws Exception {
        load("""
                one = 1
                        NOP
                """);

        String text = monitor(null, "r a=one-2\n");

        assertEquals(0, m.cpu.a);
        assertTrue(text.contains("a=one-2: value $FFFFFFFF is out of range"), text);
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a stuck Machine busy-loops
    void ctrlRightBracketHandsTheTerminalToTheMonitorAndGoHandsItBackWithNothingStolen() throws Exception {
        load("""
                loop:   LDA $D001   ; STATUS
                        BMI echo
                        AND #$20    ; input closed?
                        BEQ loop
                        LDA #0
                        STA $D300
                echo:   LDA $D000
                        STA $D000
                        JMP loop
                """);
        var in = new ByteArrayInputStream("ab\u001dr\ngo\ncd".getBytes(UTF_8));
        Terminal t = new DumbTerminal("test", Terminal.TYPE_DUMB, in, out, UTF_8);
        int code;
        try (var host = new HostConsole(t, consoleIn, () -> m.breakRequested = true)) {
            StopReason r = m.run(Set.of());
            code = new Monitor(m, t, consoleOut, symbols, host::resume, host::pause).session(r);
        }

        assertEquals(0, code); // the program saw input close after "cd"
        String text = out.toString(UTF_8);
        // the break lands before, between or after the echoes of 'a' and 'b'; the rest of the type-ahead stays the program's
        var shape = java.util.regex.Pattern.compile("(?s)((?:ab?\n)?)User break\n.*\n> (a?b?)cd").matcher(text);
        assertTrue(shape.matches(), text);
        assertEquals("ab", shape.group(1).strip() + shape.group(2), text);
        assertEquals(2, text.split("PC=", -1).length - 1, text); // the r line went to the Monitor
    }
}
