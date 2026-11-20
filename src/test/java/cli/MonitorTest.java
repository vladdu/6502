package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import disasm.SymbolFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
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
import org.junit.jupiter.api.io.TempDir;

/** The Monitor driven by lines over a DumbTerminal; asserts printed text and Machine state. */
class MonitorTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<Integer> consoleIn = new LinkedBlockingQueue<>();
    private final Monitor.ConsoleOut consoleOut = new Monitor.ConsoleOut(out);
    private final Machine m = new Machine(consoleIn, consoleOut);
    private List<SymbolFile.Symbol> symbols = List.of();
    private byte[] binary;
    /** What RunCommand would pass: restart loads the assembled program again. */
    private final Monitor.Launch launch = new Monitor.Launch(List.of("t.bin at $0200"), List.of("t.sym"), () -> m.loadProgram(binary, 0x0200));
    private int exitCode = -1;

    /** Assembles {@code body} at $0200, loads it in Program mode and keeps its symbols. */
    private void load(String body) throws Exception {
        String src = "        *= $0200\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        binary = o.binary();
        m.loadProgram(binary, 0x0200);
        symbols = SymbolFile.parse("t.sym", o.symbols());
    }

    /** Enters the Monitor with {@code reason} and feeds it {@code lines}; input EOF is Ctrl-D. Returns what it printed. */
    private String monitor(StopReason reason, String lines) throws Exception {
        Terminal t = new DumbTerminal("test", Terminal.TYPE_DUMB, new ByteArrayInputStream(lines.getBytes(UTF_8)), out, UTF_8);
        t.enterRawMode(); // as the HostConsole leaves it
        exitCode = new Monitor(m, t, consoleOut, symbols, launch, () -> {}, () -> {}).session(reason);
        t.close();
        return out.toString(UTF_8);
    }

    @Test
    void mDumpsThroughPeekSoAConsoleByteStaysUnread() throws Exception {
        load("NOP");
        consoleIn.add((int) 'x');

        String text = monitor(null, "m D000 10\n");

        assertTrue(text.contains("> D000  78 C0 00 FF FF FF FF FF FF FF FF FF FF FF FF FF  x...............\n"), text);
        assertEquals('x', consoleIn.peek());
    }

    @Test
    void bareMContinuesWhereTheLastDumpEndedFor128Bytes() throws Exception {
        load("NOP");

        String text = monitor(null, "m 200 10\nm\n");

        assertTrue(text.contains("> 0200  EA 00"), text);
        assertTrue(text.contains("> 0210  00"), text);
        assertTrue(text.contains("\n0280  00"), text);
        assertFalse(text.contains("0290"), text);
    }

    @Test
    void mWritesThroughTheBusSoADeviceSeesItAndFillsARangeWithTheBytesRepeated() throws Exception {
        load("NOP");

        String text = monitor(null, "m D000 = 41\nm 300 5 = 1 #2\nm 300 = 100\n");

        assertTrue(text.contains("> A> "), text); // the Console transmitted the byte
        for (int i = 0; i < 6; i++) assertEquals(new int[] {1, 2, 1, 2, 1, 0}[i], m.bus.peek(0x300 + i));
        assertTrue(text.contains("100: $100 is not a byte"), text);
    }

    @Test
    void anMWriteToTheExitDeviceExitsAtOnceWithThatCode() throws Exception {
        load("NOP");

        monitor(null, "m D300 = 2a\nq 1\n");

        assertEquals(42, exitCode);
    }

    @Test
    void anMWriteThatStartsTheTimerOrIssuesADiskCommandLetsTheNextStepCount() throws Exception {
        load("loop:   JMP loop"); // 3 cycles a Step

        String text = monitor(null, "m D104 = 03 00\nm D102 = 01\ns\nm D101 1\n" // one-shot of 3: done after one Step
                + "m D200 = 01\ns 55\nm D201 1\ns\nm D201 1\n"); // a 256-cycle command: busy after 85 Steps, done after 86

        assertTrue(text.contains("> D101  80 "), text);
        assertTrue(text.contains("> D201  50 "), text);
        assertTrue(text.contains("> D201  B0 "), text);
    }

    @Test
    void dListsInstructionsWithLabelsOnTheirOwnLineAndBareDContinues() throws Exception {
        load("""
                        LDX #5
                loop:   DEX
                        BNE loop
                        .byte $02
                """);

        String text = monitor(null, "d 200 2\nd\n");

        assertTrue(text.contains("""
                > 0200  A2 05     LDX #$05
                0202            loop:
                0202  CA        DEX
                > 0203  D0 FD     BNE loop
                0205  02        .byte $02 ; undocumented
                0206  00        BRK
                """), text);
        assertEquals(1 + 2 + 16, text.split("\n").length - 4, text); // stop display (3) and the final prompt line
    }

    @Test
    void dWrapsFromFFFFTo0000() throws Exception {
        load("NOP");

        String text = monitor(null, "m FFFE = A9 05 EA\nd FFFE 2\n");

        assertTrue(text.contains("""
                > FFFE  A9 05     LDA #$05
                0000  EA        NOP
                """), text);
    }

    @Test
    void nextOverARecursiveJsrStopsOnlyBackInTheOriginalFrameAndAnEmptyLineRepeatsIt() throws Exception {
        load("""
                        LDX #3
                        JSR rec
                rec:    DEX
                        BEQ done
                        JSR rec     ; next over this one, one frame down
                back:   NOP
                done:   RTS
                """);

        String text = monitor(null, "s 4\nn\n\n");

        assertTrue(text.contains("""
                > Step
                PC=020B A=00 X=00 Y=00 S=FB P=nv-bdIZc  cyc=47
                020B  EA        NOP
                """), text);
        assertEquals(0x020C, m.cpu.pc); // the empty line was another next, over the NOP
    }

    @Test
    void resetKeepsRamAndRestartsFromTheResetVector() throws Exception {
        load("NOP");

        String text = monitor(null, "m FFFC = 00 03\nm 300 = E8\nr a=5 s=10 p=0\nreset\n");

        assertTrue(text.contains("""
                > Reset
                PC=0300 A=05 X=00 Y=00 S=FD P=nv-bdIzc  cyc=0
                0300  E8        INX
                """), text);
        assertEquals(0xEA, m.bus.peek(0x0200));
    }

    @Test
    void restartZeroesRamAndReloadsTheImagesAsLaunched() throws Exception {
        load("""
                        LDA #1
                        STA $10
                """);

        String text = monitor(null, "s 2\nm 200 = EA\nm 300 = 55\nrestart\n");

        assertTrue(text.contains("""
                > Restart
                PC=0200 A=00 X=00 Y=00 S=FD P=nv-bdIzc  cyc=0
                0200  A9 01     LDA #$01
                """), text);
        assertEquals(0, m.bus.peek(0x10));
        assertEquals(0, m.bus.peek(0x300));
    }

    @Test
    void loadCopiesBytesKeepsTheRegistersAndPicksUpTheSiblingSymbols(@TempDir Path dir) throws Exception {
        load("NOP");
        Files.write(dir.resolve("sub.bin"), new byte[] {(byte) 0xE8, 0x60}); // INX; RTS
        Files.writeString(dir.resolve("sub.sym"), "0300 sub label global\n");

        String text = monitor(null, "load " + dir.resolve("sub.bin") + " 300\nd sub 1\nload " + dir.resolve("sub.bin") + "\nload " + dir.resolve("sub.bin") + " CFFF\n");

        assertTrue(text.contains("""
                > 0300            sub:
                0300  E8        INX
                """), text);
        assertEquals(0x0200, m.cpu.pc);
        assertEquals(0xE8, m.bus.peek(0x0200)); // default address
        assertTrue(text.contains(dir.resolve("sub.bin") + ": load $CFFF-$D000 overlaps the I/O region $D000-$DFFF"), text);
    }

    @Test
    void symsMergesWithTheLaterFileWinningAndRefusesAMalformedFileWhole(@TempDir Path dir) throws Exception {
        load("""
                start:  NOP
                """);
        Files.writeString(dir.resolve("more.sym"), "0300 start label global\n");
        Files.writeString(dir.resolve("bad.sym"), "0400 other label global\n; comment\n0500 x label\n");

        String text = monitor(null, "syms " + dir.resolve("more.sym") + "\nb start\nb\nsyms " + dir.resolve("bad.sym") + "\nb other\n");

        assertTrue(text.contains("> > $0300 (start)\n"), text);
        assertTrue(text.contains("> " + dir.resolve("bad.sym") + ":3: error: expected '<hex value> <name> <label|const> <global|local>'\n"), text);
        assertTrue(text.contains("'other' is not a number or symbol"), text);
    }

    @Test
    void infoShowsTheDiskTheTimerAndTheLoadedImagesAndSymbolFiles(@TempDir Path dir) throws Exception {
        load("NOP");
        Path disk = dir.resolve("d.img");
        Files.write(disk, new byte[512]);
        m.disk.attach(disk, true);
        Files.write(dir.resolve("x.bin"), new byte[] {0});

        String text = monitor(null, "info\nm D104 = 10 00\nm D102 = 03\nload " + dir.resolve("x.bin") + " 300\ninfo\n");

        assertTrue(text.contains("> Disk: " + disk + ", 512 bytes, read-only, idle\n"
                + "Timer: stopped, one-shot, IRQ off, flag clear, reload $0000\n"
                + "Images: t.bin at $0200\n"
                + "Symbol files: t.sym\n"), text);
        assertTrue(text.contains("Timer: running, periodic, IRQ off, flag clear, reload $0010\n"
                + "Images: t.bin at $0200, " + dir.resolve("x.bin") + " at $0300\n"), text);
    }

    @Test
    void infoWithNoDiskSaysNoMedia() throws Exception {
        load("NOP");

        assertTrue(monitor(null, "info\n").contains("> Disk: no media\n"));
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

        for (String c : List.of("go", "continue", "step", "next", "b ADDR", "b del", "r", "m [ADDR", "m ADDR [LEN] =", "d [ADDR",
                "load FILE", "syms FILE", "reset", "restart", "info", "quit", "help")) assertTrue(text.contains(c), c);
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
            code = new Monitor(m, t, consoleOut, symbols, launch, host::resume, host::pause).session(r);
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
