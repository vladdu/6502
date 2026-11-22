package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
        String src = "        .defsection code, $0200\n        .section code\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), _ -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        Files.write(launchDir.resolve("t.bin"), o.binary());
        Files.writeString(launchDir.resolve("t.sym"), o.symbols());
        var launch = new Launch(m, null, null, launchDir.resolve("t.bin"), 0x0200, null, false, List.of());
        interpreter = new Interpreter(m, new PrintWriter(out), consoleOut, launch.start(), launch, () -> hooks.add("resume"), () -> hooks.add("pause"));
    }

    /** Assembles {@code body} at {@code origin} to NAME.bin and NAME.sym, recording {@code entryPoint} if not null. */
    private Path image(String name, String origin, String entryPoint, String body) throws Exception {
        String src = ".defsection code, $" + origin + "\n.section code\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), _ -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var o = (Assembler.Output) r;
        String symbols = entryPoint == null ? o.symbols() : o.symbols().replaceFirst("^(image \\w+) \\w+", "$1 " + entryPoint);
        Files.writeString(launchDir.resolve(name + ".sym"), symbols);
        return Files.write(launchDir.resolve(name + ".bin"), o.binary());
    }

    /** Launches {@code image} with {@code org} typed if not null, then builds the interpreter over it. */
    private void launch(Path image, Integer org) throws Exception {
        var launch = new Launch(m, null, null, image, org, null, false, List.of());
        interpreter = new Interpreter(m, new PrintWriter(out), consoleOut, launch.start(), launch, () -> {}, () -> {});
    }

    @Test
    void loadDefaultsToTheImageOriginAndLeavesPcAlone() throws Exception {
        load("NOP");
        Path sub = image("sub", "0400", "0401", "sub: INX\nRTS");

        interpreter.execute("load " + sub);
        interpreter.execute("info");

        assertEquals(0xE8, m.bus.peek(0x0400));
        assertEquals(0x0200, m.cpu.pc);
        assertEquals(0x0400, interpreter.symbols().value("sub"));
        assertTrue(out.toString().contains(", " + sub + " at $0400\n"), out.toString()); // no Entry point: load doesn't start it
        assertFalse(out.toString().contains("warning"), out.toString());
    }

    @Test
    void loadAtATypedAddressWarnsWhenItIsNotTheImageOriginAndDoesNotShiftTheSymbols() throws Exception {
        load("NOP");
        Path sub = image("sub", "0400", null, "sub: INX");

        interpreter.execute("load " + sub + " 600");

        assertEquals("warning: " + sub + ": loaded at $0600, its Image origin is $0400; symbols are for $0400\n", out.toString());
        assertEquals(0xE8, m.bus.peek(0x0600));
        assertEquals(0x0400, interpreter.symbols().value("sub"));
    }

    @Test
    void loadOfAMismatchedImageIsRefusedWithoutAnAddressAndIgnoresItsSymbolFileWithOne() throws Exception {
        load("NOP");
        Path sub = image("sub", "0400", null, "sub: INX");
        Files.write(sub, new byte[] {(byte) 0xC8});
        Path sym = launchDir.resolve("sub.sym");

        interpreter.execute("load " + sub);

        assertEquals(sym + " does not match " + sub + ": assemble again, remove it, or give ADDR\n", out.toString());
        assertEquals(0xEA, m.bus.peek(0x0200));
        assertEquals(0, m.bus.peek(0x0400));
        assertNull(interpreter.symbols().value("sub"));

        out.getBuffer().setLength(0);
        interpreter.execute("load " + sub + " 600");

        assertEquals("warning: " + sym + " does not match " + sub + ": ignored\n", out.toString());
        assertEquals(0xC8, m.bus.peek(0x0600));
        assertNull(interpreter.symbols().value("sub"));
    }

    @Test
    void aLoadRefusedForItsAddressOrSymbolFileChangesNeitherMemoryNorSymbols() throws Exception {
        load("NOP");
        Path sub = image("sub", "0400", null, "sub: INX");

        interpreter.execute("load " + sub + " D000");
        Files.writeString(launchDir.resolve("sub.sym"), "0400 sub label\n");
        interpreter.execute("load " + sub + " 600");

        assertEquals(0, m.bus.peek(0x0600));
        assertNull(interpreter.symbols().value("sub"));
    }

    @Test
    void symsOfAFileWithAForeignRecordOnlyAddsItsSymbols() throws Exception {
        load("NOP");
        Path os = Files.writeString(launchDir.resolve("os.sym"), "image E000 0001 12345678\nE000 os label local\n");

        interpreter.execute("syms " + os);

        assertEquals("", out.toString());
        assertEquals(0xE000, interpreter.symbols().value("os"));
        assertEquals(0x0200, m.cpu.pc);
    }

    @Test
    void infoShowsTheEntryPointOfALaunchedImageOnlyWhenItIsNotTheLoadAddress() throws Exception {
        Path prog = image("prog", "0400", "0403", ".res 3\nNOP");
        launch(prog, null);
        interpreter.execute("info");
        assertTrue(out.toString().contains("Images: " + prog + " at $0400 (Entry point $0403)\n"), out.toString());

        out.getBuffer().setLength(0);
        launch(image("prog", "0400", null, "NOP"), null);
        interpreter.execute("info");
        assertTrue(out.toString().contains("Images: " + prog + " at $0400\n"), out.toString());
    }

    @Test
    void restartAfterReassemblyUsesTheNewImageOriginAndEntryPoint() throws Exception {
        Path prog = image("prog", "0400", null, "old: NOP");
        launch(prog, null);
        image("prog", "0500", "0501", "fresh: NOP\nINX");

        interpreter.execute("restart");
        interpreter.execute("info");

        assertEquals(0x0501, m.cpu.pc);
        assertEquals(0xEA, m.bus.peek(0x0500));
        assertEquals(0, m.bus.peek(0x0400));
        assertNull(interpreter.symbols().value("old"));
        assertTrue(out.toString().contains(prog + " at $0500 (Entry point $0501)\n"), out.toString());
    }

    @Test
    void restartKeepsATypedAddressAndRepeatsTheWarning() throws Exception {
        Path prog = image("prog", "0400", null, "NOP");
        launch(prog, 0x0600);
        image("prog", "0500", "0501", "NOP\nINX");

        interpreter.execute("restart");

        assertEquals(0x0601, m.cpu.pc);
        assertTrue(out.toString().startsWith("warning: " + prog + ": loaded at $0600, its Image origin is $0500; symbols are for $0500\nRestart\n"),
                out.toString());
    }

    @Test
    void restartOfAMismatchedImageIsRefusedKeepingTheSession() throws Exception {
        Path prog = image("prog", "0400", null, "old: NOP");
        launch(prog, null);
        m.bus.write(0x0300, 0x55);
        m.cpu.a = 7;
        Files.write(prog, new byte[] {(byte) 0xE8});

        interpreter.execute("restart");

        assertEquals(launchDir.resolve("prog.sym") + " does not match " + prog + ": assemble again or remove it\n", out.toString());
        assertEquals(0x55, m.bus.peek(0x0300));
        assertEquals(7, m.cpu.a);
        assertEquals(0x0400, interpreter.symbols().value("old"));
    }

    @Test
    void restartOfAnImageReassembledWhereItCantLoadIsRefusedKeepingTheSession() throws Exception {
        Path prog = image("prog", "0400", null, "old: NOP");
        launch(prog, null);
        m.bus.write(0x0300, 0x55);
        m.cpu.a = 7;
        image("prog", "D000", null, "fresh: NOP");

        interpreter.execute("restart");

        assertEquals(prog + ": load $D000-$D000 overlaps $D000-$D0FF, which is not RAM\n", out.toString());
        assertEquals(0x55, m.bus.peek(0x0300));
        assertEquals(0xEA, m.bus.peek(0x0400));
        assertEquals(7, m.cpu.a);
        assertEquals(0x0400, m.cpu.pc);
        assertEquals(0x0400, interpreter.symbols().value("old"));
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
    void untilRunsToAddrWithoutReadingAsABreakpointAndTheOneShotIsGoneAfterwards() throws Exception {
        load("""
                loop:   INX
                there:  CPX #3
                        BNE loop
                        BRK
                """);

        var r = interpreter.execute("until there");
        interpreter.execute("b");
        var after = interpreter.execute("go");

        assertEquals(new Interpreter.Stopped(new StopReason.Breakpoint(0x0201)), r);
        assertEquals(new Interpreter.Stopped(new StopReason.StrayInterrupt(0xFFFE)), after); // go ran past there
        assertTrue(out.toString().startsWith("""
                Reached $0201 (there)
                PC=0201 A=00 X=01 Y=00 S=FD P=nv-bdIzc  cyc=2
                0201  E0 03     CPX #$03
                no breakpoints
                """), out.toString());
    }

    @Test
    void anotherStopEndsUntilFirstWithItsOwnStopAndTheOneShotIsGoneEitherWay() throws Exception {
        load("""
                        STA $0300
                        NOP
                bp:     NOP
                there:  NOP
                        NOP
                        BRK
                """);
        interpreter.execute("w 300");
        interpreter.execute("b bp");

        var watch = interpreter.execute("until there");
        var breakpoint = interpreter.execute("until there");
        interpreter.execute("w del 300");
        interpreter.execute("r pc=0");
        var stray = interpreter.execute("until 0");
        interpreter.execute("b");

        assertEquals(new Interpreter.Stopped(new StopReason.Watchpoint(0x0300, true, 0x00, 0x00, "$0200", 0)), watch);
        assertEquals(new Interpreter.Stopped(new StopReason.Breakpoint(0x0204)), breakpoint);
        assertEquals(new Interpreter.Stopped(new StopReason.StrayInterrupt(0xFFFE)), stray); // BRK at $0000, zeroed RAM
        assertTrue(out.toString().contains("Breakpoint at $0204 (bp)\n"), out.toString());
        assertTrue(out.toString().endsWith("\n$0204 (bp)\n"), out.toString());
    }

    @Test
    void untilStopsAtAUserBreakpointOnAddrAsThatBreakpointAndLeavesIt() throws Exception {
        load("""
                        NOP
                there:  NOP
                        JMP there
                """);
        interpreter.execute("b there");

        var r = interpreter.execute("until there");
        interpreter.execute("b");

        assertEquals(new Interpreter.Stopped(new StopReason.Breakpoint(0x0201)), r);
        assertEquals("""
                Breakpoint at $0201 (there)
                PC=0201 A=00 X=00 Y=00 S=FD P=nv-bdIzc  cyc=2
                0201  EA        NOP
                $0201 (there)
                """, out.toString());
    }

    @Test
    void untilRefusesAMissingOrBadAddressAndHelpListsIt() throws Exception {
        load("NOP");

        for (String line : List.of("until", "until 200 300", "until x", "until 10000")) interpreter.execute(line);
        String errors = out.toString();
        interpreter.execute("help");

        assertEquals("""
                usage: until ADDR
                usage: until ADDR
                'x' is not a number or symbol
                10000: $10000 is not an address
                """, errors);
        assertEquals(0x0200, m.cpu.pc);
        assertTrue(out.toString().contains("until ADDR "), out.toString());
    }

    @Test
    void helpListsW() throws Exception {
        load("NOP");

        interpreter.execute("help");

        assertTrue(out.toString().contains("w [r|rw] ADDR [LEN]"), out.toString());
    }

    @Test
    void nmiPressesTheNmiButtonSoTheNextStepIsTheNmiEntry() throws Exception {
        load("""
                        NOP
                handler: RTI
                """);
        interpreter.execute("m FFFA = 01 02");

        interpreter.execute("nmi x");
        interpreter.execute("nmi");
        interpreter.execute("step");

        assertTrue(out.toString().startsWith("usage: nmi\nNMI entry -> $0201\n"), out.toString());
        out.getBuffer().setLength(0);
        interpreter.execute("help");
        assertTrue(out.toString().contains("\nnmi "), out.toString());
    }

    @Test
    void infoHasAConsoleLineBeforeTheTimer() throws Exception {
        load("NOP");

        interpreter.execute("m D002 = 80");
        interpreter.execute("info");

        assertTrue(out.toString().startsWith("Console: 0 bytes waiting, no EOF, IRQ on\nTimer: stopped,"), out.toString());
    }
    /** A 2-Sector image in the test's directory. */
    private Path disk(String name) throws Exception {
        Path p = launchDir.resolve(name);
        Files.write(p, new byte[512]);
        return p;
    }

    @Test
    void diskShowsTheImageAndInsertsSwapsEjectsAndWriteProtectsIt() throws Exception {
        load("NOP");
        Path a = disk("a.img"), b = disk("b.img");

        for (String line : List.of("disk", "disk " + a, "disk", "disk ro", "disk", "disk rw", "disk", "disk " + b + " ro",
                "disk", "disk eject", "disk")) interpreter.execute(line);

        assertEquals("Disk: no media\n"
                + "Disk: " + a + ", 512 bytes, read-write, idle\n"
                + "Disk: " + a + ", 512 bytes, read-only, idle\n"
                + "Disk: " + a + ", 512 bytes, read-write, idle\n"
                + "Disk: " + b + ", 512 bytes, read-only, idle\n"
                + "Disk: no media\n", out.toString());
        assertNull(m.disk.state().image());
    }

    @Test
    void diskRefusesAnEmptyDriveAndRejectedImagesKeepingTheOldOne() throws Exception {
        load("NOP");
        Path a = disk("a.img"), empty = launchDir.resolve("empty.img"), missing = launchDir.resolve("missing.img");
        Files.write(empty, new byte[0]);

        for (String line : List.of("disk ro", "disk rw", "disk " + a, "disk " + empty, "disk " + missing)) interpreter.execute(line);

        assertEquals("no Disk image\n"
                + "no Disk image\n"
                + empty + ": disk image is empty\n"
                + "cannot read " + missing + ": java.nio.file.NoSuchFileException: " + missing + "\n", out.toString());
        assertEquals(a, m.disk.state().image());
        assertFalse(m.disk.state().readOnly());
    }

    @Test
    void diskTakesTheRestOfTheLineAsTheFileSoAPathMayHoldSpaces() throws Exception {
        load("NOP");
        Path a = disk("my disk.img");

        interpreter.execute("disk " + a);
        assertEquals(a, m.disk.state().image());
        interpreter.execute("disk eject");
        interpreter.execute("disk  " + a + "  ro");

        assertEquals("", out.toString());
        assertEquals(a, m.disk.state().image());
        assertTrue(m.disk.state().readOnly());
    }

    @Test
    void anUnwritableImageInsertsReadOnlyWithAWarningAndDiskRwIsRefused() throws Exception {
        load("NOP");
        Path a = disk("a.img");
        assertTrue(a.toFile().setWritable(false));
        assumeFalse(Files.isWritable(a), "running as a user who can write anyway");

        interpreter.execute("disk " + a);
        interpreter.execute("disk rw");

        assertEquals("warning: " + a + ": not writable, Disk mounted read-only\n" + a + ": not writable\n", out.toString());
        assertTrue(m.disk.state().readOnly());
    }

    @Test
    void restartKeepsTheDiskImageInsertedWithDisk() throws Exception {
        load("NOP");
        Path a = disk("a.img");
        interpreter.execute("disk " + a + " ro");

        interpreter.execute("restart");

        assertEquals(a, m.disk.state().image());
        assertTrue(m.disk.state().readOnly());
    }

    @Test
    void helpListsDisk() throws Exception {
        load("NOP");

        interpreter.execute("help");

        assertTrue(out.toString().contains("\ndisk [FILE [ro]] "), out.toString());
        assertTrue(Interpreter.COMMANDS.contains("disk"));
    }
}
