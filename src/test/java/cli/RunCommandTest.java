package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class RunCommandTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    private int run(String... args) {
        return runWithInput(new byte[0], args);
    }

    private int runWithInput(byte[] stdin, String... args) {
        return Main.execute(args, new ByteArrayInputStream(stdin), new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
    }

    private String image(int... b) throws IOException {
        var bytes = new byte[b.length];
        for (int i = 0; i < b.length; i++) bytes[i] = (byte) b[i];
        return write("prog.bin", bytes);
    }

    private String write(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p.toString();
    }

    /**
     * A 256-byte ROM at $FF00, as the Assembler would emit it (hand-assembled until `asm` lands):
     * <pre>
     *         .defsection code,    $FF00
     *         .defsection table,   $FF20
     *         .defsection vectors, $FFFA
     *         .section code
     * reset:  LDA $FF20     ; AD 20 FF   the byte at $FF20 is the exit code
     *         STA $D300     ; 8D 00 D3
     * nmi:
     * irq:    JMP irq       ; 4C 06 FF
     *         .section table
     *         .byte 7
     *         .section vectors
     *         .word nmi, reset, irq
     * </pre>
     */
    private static byte[] rom() {
        var b = new byte[0x100];
        int[] code = {0xAD, 0x20, 0xFF, 0x8D, 0x00, 0xD3, 0x4C, 0x06, 0xFF};
        for (int i = 0; i < code.length; i++) b[i] = (byte) code[i];
        b[0x20] = 7;
        int[] vectors = {0x06, 0xFF, 0x00, 0xFF, 0x06, 0xFF};
        for (int i = 0; i < vectors.length; i++) b[0xFA + i] = (byte) vectors[i];
        return b;
    }

    /** Assembles {@code src} to prog.bin and prog.sym; returns the image's path. */
    private String assemble(String src) throws IOException {
        Path source = Files.writeString(dir.resolve("prog.s"), src);
        assertEquals(0, run("asm", source.toString()), err.toString(UTF_8));
        return dir.resolve("prog.bin").toString();
    }

    /** Makes prog.sym's image line record {@code hex} as the Entry point, keeping origin and checksum. */
    private void recordEntryPoint(String hex) throws IOException {
        Path sym = dir.resolve("prog.sym");
        Files.writeString(sym, Files.readString(sym).replaceFirst("^(image \\w+) \\w+", "$1 " + hex));
    }

    /** {@code main}, which exits 7, in a Section code at $0400 after {@code above}. */
    private static String at0400(String above) {
        return ".defsection code, $0400\n.section code\n" + above + "main: lda #7\nsta $D300\n";
    }

    @Test
    void anAssembledImageLoadsAndStartsAtItsImageOrigin() throws IOException {
        assertEquals(7, run("run", assemble(at0400("jmp main\n")))); // only at $0400 does the jmp land on main
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void programModeStartsAtTheRecordedEntryPoint() throws IOException {
        String img = assemble(at0400(".res 3\n"));
        recordEntryPoint("0403");
        assertEquals(7, run("run", img));
        assertEquals("", err.toString(UTF_8));
    }

    private static final String EXIT_0_ABOVE_MAIN = at0400("quit: lda #0\nsta $D300\n");
    private static final String RTS_ABOVE_MAIN = at0400("sub: rts\n");
    private static final String DATA_BELOW_THE_CODE = ".defsection data, $0300\n" + at0400("") + ".section data\n.byte 0\n";

    @Test
    void withEntryMainCodeAndDataBelowMainDontBecomeTheEntryPoint() throws IOException {
        for (String src : new String[] {RTS_ABOVE_MAIN, EXIT_0_ABOVE_MAIN, DATA_BELOW_THE_CODE}) {
            err.reset();
            assertEquals(7, run("run", assemble(".entry main\n" + src)), src);
            assertEquals("", err.toString(UTF_8));
        }
    }

    @Test
    void withoutEntryTheSameProgramsStartAtTheirFirstByte() throws IOException {
        assertEquals(125, run("run", assemble(RTS_ABOVE_MAIN)));
        assertEquals(0, run("run", assemble(EXIT_0_ABOVE_MAIN)));
        assertEquals(125, run("run", assemble(DATA_BELOW_THE_CODE)));
    }

    @Test
    void entryMainAfterThreeFillBytesStartsAt0603WhenLoadedAt0600() throws IOException {
        String img = assemble(".entry main\n.defsection code, $0400\n.section code\n.res 3\nmain: lda #7\nsta $D300\n");
        assertEquals(7, run("run", img, "--org", "$0600"));
        assertTrue(err.toString(UTF_8).contains("loaded at $0600, its Image origin is $0400"), err.toString(UTF_8));
        err.reset();
        assertEquals(7, run("run", img, "--org", "$0400"));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void aTypedLoadAddressWinsWithAWarningAndTakesTheEntryPointAlong() throws IOException {
        String img = assemble(at0400(".res 3\n"));
        recordEntryPoint("0403");
        assertEquals(7, run("run", img, "--org", "$0600"));
        assertEquals("6502: warning: " + img + ": loaded at $0600, its Image origin is $0400; symbols are for $0400\n", err.toString(UTF_8));
    }

    @Test
    void aTyped0200IsATypedAddressAndNotTheDefault() throws IOException {
        String img = assemble(at0400(".res 3\n"));
        recordEntryPoint("0403");
        assertEquals(7, run("run", img, "--org", "$0200"));
        assertEquals("6502: warning: " + img + ": loaded at $0200, its Image origin is $0400; symbols are for $0400\n", err.toString(UTF_8));
    }

    @Test
    void aTypedLoadAddressEqualToTheImageOriginGetsNoWarning() throws IOException {
        String img = assemble(at0400(".res 3\n"));
        recordEntryPoint("0403");
        assertEquals(7, run("run", img, "--org", "$0400"));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void anImageThatDoesNotMatchItsSymbolFileIsRefused() throws IOException {
        String img = assemble(at0400(""));
        image(0xA9, 0x08, 0x8D, 0x00, 0xD3); // the same size
        assertEquals(125, run("run", img));
        assertEquals("6502: " + dir.resolve("prog.sym") + " does not match " + img + ": assemble again, remove it, or give --org\n",
                err.toString(UTF_8));
    }

    @Test
    void withATypedAddressAMismatchedSymbolFileIsIgnoredWithAWarning() throws IOException {
        String img = assemble(at0400(".res 3\n"));
        recordEntryPoint("0403");
        image(0xA9, 0x09, 0x8D, 0x00, 0xD3, 0xEA, 0xEA, 0xEA); // the same size, exits 9 from its first byte
        assertEquals(9, run("run", img, "--org", "$0600"));
        assertEquals("6502: warning: " + dir.resolve("prog.sym") + " does not match " + img + ": ignored\n", err.toString(UTF_8));
    }

    @Test
    void anEntryPointOutsideTheImageOfAMatchingRecordFailsNamingTheFileAlsoWithATypedAddress() throws IOException {
        String img = assemble(at0400("")); // $0400-$0404
        for (String outside : new String[] {"03FF", "0405"}) {
            recordEntryPoint(outside);
            for (String[] args : new String[][] {{"run", img}, {"run", img, "--org", "$0600"}}) {
                err.reset();
                assertEquals(125, run(args));
                assertEquals("6502: " + dir.resolve("prog.sym") + ":1: error: Entry point $" + outside + " is outside the image\n",
                        err.toString(UTF_8));
            }
        }
    }

    @Test
    void aBadImageLineFailsAlsoWithATypedAddress() throws IOException {
        String img = assemble(at0400(""));
        Path sym = dir.resolve("prog.sym");
        String line = Files.readAllLines(sym).getFirst() + "\n";
        for (String bad : new String[] {"image 0400\n", line + line}) {
            Files.writeString(sym, bad);
            err.reset();
            assertEquals(125, run("run", img, "--org", "$0600"));
            assertTrue(err.toString(UTF_8).startsWith("6502: " + sym + ":"), err.toString(UTF_8));
        }
    }

    @Test
    void withoutASymbolFileOrAnImageLineAnImageStartsAt0200Silently() throws IOException {
        String img = assemble(".defsection code, $0200\n.section code\njmp main\nmain: lda #7\nsta $D300\n");
        Files.writeString(dir.resolve("prog.sym"), "0203 main label local\n");
        assertEquals(7, run("run", img));
        Files.delete(dir.resolve("prog.sym"));
        assertEquals(7, run("run", img));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void exitDeviceWriteBecomesTheExitCode() throws IOException {
        assertEquals(42, run("run", image(0xA9, 0x2A, 0x8D, 0x00, 0xD3)));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void defaultOrgIs0200() throws IOException {
        // JMP $0206; at $0206 (offset 6 when loaded at $0200): LDA #7; STA $D300
        assertEquals(7, run("run", image(0x4C, 0x06, 0x02, 0xDB, 0xDB, 0xDB, 0xA9, 0x07, 0x8D, 0x00, 0xD3)));
    }

    @Test
    void orgAcceptsHexAndDecimal() throws IOException {
        // JMP $0306; LDA #9; STA $D300 at offset 6: only exits 9 when loaded at $0300
        String img = image(0x4C, 0x06, 0x03, 0xDB, 0xDB, 0xDB, 0xA9, 0x09, 0x8D, 0x00, 0xD3);
        assertEquals(9, run("run", img, "--org", "$0300"));
        assertEquals(9, run("run", img, "--org", "0x300"));
        assertEquals(9, run("run", img, "--org", "768"));
    }

    @Test
    void loadOverlappingTheIoRegionExits125() throws IOException {
        assertEquals(125, run("run", image(0xEA, 0xEA), "--org", "$CFFF"));
        assertTrue(err.toString(UTF_8).contains("overlaps $D000-$D0FF, which is not RAM"), err.toString(UTF_8));
    }

    @Test
    void aLoadErrorStillClosesTheTerminal() throws IOException {
        String img = image(0xEA);
        for (String[] args : new String[][] {{"run", img, "--org", "$D000"}, {"run", img}}) {
            var closed = new boolean[1];
            var in = new ByteArrayInputStream(new byte[0]) {
                @Override
                public void close() {
                    closed[0] = true;
                }
            };
            Main.execute(args, in, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
            assertTrue(closed[0], String.join(" ", args));
        }
    }

    @Test
    void missingImageFileExits125() {
        assertEquals(125, run("run", dir.resolve("nope.bin").toString()));
        assertFalse(err.toString(UTF_8).isEmpty());
    }

    @Test
    void noImageIsAUsageError() {
        assertEquals(2, run("run"));
    }

    @Test
    void badOrgIsAUsageError() throws IOException {
        assertEquals(2, run("run", image(0xEA), "--org", "$zz"));
    }

    @Test
    void illegalOpcodeExits125WithTheReasonOnStderr() throws IOException {
        assertEquals(125, run("run", image(0xEA, 0xDB)));
        assertEquals("illegal opcode $DB at $0201", err.toString(UTF_8).strip());
    }

    @Test
    void strayInterruptExits125WithTheReasonOnStderr() throws IOException {
        assertEquals(125, run("run", image(0x00))); // BRK, no vector installed
        assertEquals("stray interrupt: vector $FFFE = $0000", err.toString(UTF_8).strip());
    }

    @Test
    void romBootRunsFromTheResetVectorAndExits() throws IOException {
        assertEquals(7, run("run", "--rom", write("rom.bin", rom())));
        assertEquals("", err.toString(UTF_8));
    }

    /** A system ROM assembled to prog.bin: code at $E000 that exits 7, its vectors then {@code after} from $FFFA. */
    private String assembleRom(String after) throws IOException {
        return assemble("""
                        .defsection code,    $E000
                        .defsection vectors, $FFFA
                        .section code
                irq:    rti
                reset:  lda #7
                        sta $D300
                        .section vectors
                        .word irq, reset
                """ + after);
    }

    @Test
    void aRomImageWhoseOriginAndSizeDoNotEndAtFFFFIsRefusedWithOrWithoutAnImage() throws IOException {
        String rom = assembleRom("");
        String app = write("app.bin", new byte[] {(byte) 0xEA});
        for (String[] args : new String[][] {{"run", "--rom", rom}, {"run", app, "--rom", rom}}) {
            err.reset();
            assertEquals(125, run(args));
            assertEquals("6502: " + rom + ": Image origin $E000 and 8190 bytes end at $FFFD; a ROM image must end at $FFFF\n",
                    err.toString(UTF_8));
        }
    }

    @Test
    void aRomImagePaddedToFFFFBootsThroughTheResetVectorNotItsEntryPoint() throws IOException {
        assertEquals(7, run("run", "--rom", assembleRom(".word irq\n"))); // the recorded Entry point is the RTI
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void aRomBesideAnotherImagesSymbolFileIsRefusedAlsoWithATypedAddress() throws IOException {
        String rom = write("a.rom", rom());
        String sym = write("a.sym", "image 0200 0200 92DDE4EB\n".getBytes(UTF_8));
        String app = write("app.bin", new byte[] {(byte) 0xEA});
        for (String[] args : new String[][] {{"run", "--rom", rom}, {"run", "--rom", rom, "--org", "$E000"},
                {"run", app, "--rom", rom, "--org", "$0300"}}) {
            err.reset();
            assertEquals(125, run(args));
            assertEquals("6502: " + sym + " does not match " + rom + ": assemble again or remove it\n", err.toString(UTF_8));
        }
    }

    @Test
    void writesIntoTheAttachedRomAreIgnored() throws IOException {
        // LDA #$11; STA $FF20; LDA $FF20; STA $D300: exits 7 (the ROM byte), not $11
        String prog = image(0xA9, 0x11, 0x8D, 0x20, 0xFF, 0xAD, 0x20, 0xFF, 0x8D, 0x00, 0xD3);
        assertEquals(7, run("run", prog, "--rom", write("rom.bin", rom())));
    }

    @Test
    void romOver8KExits125() throws IOException {
        assertEquals(125, run("run", "--rom", write("big.bin", new byte[9 * 1024])));
        assertTrue(err.toString(UTF_8).contains("larger than its area $E000-$FFFF"), err.toString(UTF_8));
    }

    @Test
    void romBootWithTheResetVectorOutsideTheImageExits125() throws IOException {
        byte[] rom = rom();
        rom[0xFD] = (byte) 0xE0; // reset vector $E000, below the 256-byte image
        assertEquals(125, run("run", "--rom", write("rom.bin", rom)));
        assertTrue(err.toString(UTF_8).contains("reset vector"), err.toString(UTF_8));
    }

    @Test
    void romPlusProgramStartsInProgramModeAtOrg() throws IOException {
        // LDA $FF20; STA $D300 at $0300: exits with the ROM byte only if the ROM is attached and PC = org
        String prog = image(0xAD, 0x20, 0xFF, 0x8D, 0x00, 0xD3);
        byte[] rom = rom();
        rom[0x20] = 5;
        rom[0xFD] = (byte) 0xE0; // a bad reset vector doesn't matter in Program mode
        assertEquals(5, run("run", prog, "--org", "$0300", "--rom", write("rom.bin", rom)));
    }

    @Test
    void programOverlappingTheAttachedRomExits125() throws IOException {
        assertEquals(125, run("run", image(0xEA), "--org", "$E000", "--rom", write("rom.bin", rom())));
        assertTrue(err.toString(UTF_8).contains("overlaps $E000-$FFFF, which is not RAM"), err.toString(UTF_8));
    }

    /**
     * Echoes Console input until input closes, then exits 0:
     * <pre>
     * loop:   LDA $D001     ; AD 01 D0   STATUS
     *         BMI echo      ; 30 09
     *         AND #$20      ; 29 20      input closed?
     *         BEQ loop      ; F0 F7
     *         LDA #0        ; A9 00
     *         STA $D300     ; 8D 00 D3
     * echo:   LDA $D000     ; AD 00 D0
     *         STA $D000     ; 8D 00 D0
     *         JMP loop      ; 4C 00 02
     * </pre>
     */
    private String echo() throws IOException {
        return image(0xAD, 0x01, 0xD0, 0x30, 0x09, 0x29, 0x20, 0xF0, 0xF7, 0xA9, 0x00, 0x8D, 0x00, 0xD3,
                0xAD, 0x00, 0xD0, 0x8D, 0x00, 0xD0, 0x4C, 0x00, 0x02);
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a stuck Machine busy-loops
    void echoProgramEchoesPipedInputAndExitsWhenInputCloses() throws IOException {
        assertEquals(0, runWithInput("hi\n".getBytes(UTF_8), "run", echo()));
        assertEquals("hi\n", out.toString(UTF_8));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void utf8InputRoundTripsThroughTheEchoProgram() throws IOException {
        String text = "é€\uD83D\uDE00\r"; // 2-, 3- and 4-byte UTF-8, then CR untranslated
        assertEquals(0, runWithInput(text.getBytes(UTF_8), "run", echo()));
        assertArrayEquals(text.getBytes(UTF_8), out.toByteArray());
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void ctrlCReachesTheProgramAsAByte() throws IOException {
        assertEquals(0, runWithInput(new byte[] {'a', 0x03, 'b'}, "run", echo()));
        assertArrayEquals(new byte[] {'a', 0x03, 'b'}, out.toByteArray());
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void ctrlRightBracketInPipedInputReachesTheProgramAsAByte() throws IOException {
        // Piped stdin is not interactive: there is no Monitor to hand over to, so Ctrl-] is just data.
        assertEquals(0, runWithInput(new byte[] {'a', 0x1D, 'b'}, "run", echo()));
        assertArrayEquals(new byte[] {'a', 0x1D, 'b'}, out.toByteArray());
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void speedThrottlesSoAboutAMillionCyclesTakeAboutASecond() throws IOException {
        // 256 x 256 x (5 NOPs, DEX, BNE) and the rest: 984,581 cycles, then exit 0: about 1 s at 0.985 MHz
        String img = image(
                0xA0, 0x00,                   //       LDY #0
                0xA2, 0x00,                   // l1:   LDX #0
                0xEA, 0xEA, 0xEA, 0xEA, 0xEA, // l2:   NOP x5
                0xCA,                         //       DEX
                0xD0, 0xF8,                   //       BNE l2
                0x88,                         //       DEY
                0xD0, 0xF3,                   //       BNE l1
                0x8D, 0x00, 0xD3);            //       STA $D300 (A=0)
        long t = System.nanoTime();
        assertEquals(0, run("run", img, "--speed", "0.985"));
        double s = (System.nanoTime() - t) / 1e9;
        assertTrue(s >= 0.95 && s < 5, s + " s");
    }

    @Test
    void speedMustBeAPositiveNumber() throws IOException {
        for (String bad : new String[] {"0", "-1", "fast", "NaN"}) {
            err.reset();
            assertEquals(2, run("run", image(0xEA), "--speed", bad), bad);
            assertTrue(err.toString(UTF_8).contains("--speed"), err.toString(UTF_8));
        }
    }

    @Test
    void monitorWithPipedStdinIsAUsageError() throws IOException {
        assertEquals(2, run("run", image(0xEA), "--monitor"));
        assertTrue(err.toString(UTF_8).contains("--monitor needs an interactive terminal"), err.toString(UTF_8));
    }

    @Test
    void guiWithoutADisplayIsAUsageError() throws IOException {
        assertTrue(java.awt.GraphicsEnvironment.isHeadless(), "the default tests run headless");
        assertEquals(2, run("run", image(0xEA), "--gui", "--monitor"));
        assertTrue(err.toString(UTF_8).contains("--gui needs a display"), err.toString(UTF_8));
    }

    @Test
    void theSymbolFileNextToTheImageLoadsByItself() throws IOException {
        String prog = image(0xA9, 0x00, 0x8D, 0x00, 0xD3);
        write("prog.sym", "0200 start nolabel local\n".getBytes(UTF_8));
        assertEquals(125, run("run", prog));
        assertTrue(err.toString(UTF_8).contains("prog.sym:1: error: expected label or const"), err.toString(UTF_8));
    }

    @Test
    void symsFilesLoadAndAMissingOneExits125() throws IOException {
        String prog = image(0xA9, 0x00, 0x8D, 0x00, 0xD3);
        String good = write("a.sym", "0200 start label local\n".getBytes(UTF_8));
        assertEquals(0, run("run", prog, "--syms", good));
        assertEquals(125, run("run", prog, "--syms", good, "--syms", dir.resolve("none.sym").toString()));
        assertTrue(err.toString(UTF_8).contains("cannot read " + dir.resolve("none.sym")), err.toString(UTF_8));
    }
}
