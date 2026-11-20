package cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.execute(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true), new PrintStream(err, true));
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
     *         *= $FF00
     * reset:  LDA $FF20     ; AD 20 FF   the byte at $FF20 is the exit code
     *         STA $D300     ; 8D 00 D3
     * nmi:
     * irq:    JMP irq       ; 4C 06 FF
     *         *= $FF20
     *         .byte 7
     *         *= $FFFA
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

    @Test
    void exitDeviceWriteBecomesTheExitCode() throws IOException {
        assertEquals(42, run("run", image(0xA9, 0x2A, 0x8D, 0x00, 0xD3)));
        assertEquals("", err.toString());
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
        assertTrue(err.toString().contains("I/O region"), err.toString());
    }

    @Test
    void missingImageFileExits125() {
        assertEquals(125, run("run", dir.resolve("nope.bin").toString()));
        assertFalse(err.toString().isEmpty());
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
        assertEquals("illegal opcode $DB at $0201", err.toString().strip());
    }

    @Test
    void romBootRunsFromTheResetVectorAndExits() throws IOException {
        assertEquals(7, run("run", "--rom", write("rom.bin", rom())));
        assertEquals("", err.toString());
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
        assertTrue(err.toString().contains("8K"), err.toString());
    }

    @Test
    void romBootWithTheResetVectorOutsideTheImageExits125() throws IOException {
        byte[] rom = rom();
        rom[0xFD] = (byte) 0xE0; // reset vector $E000, below the 256-byte image
        assertEquals(125, run("run", "--rom", write("rom.bin", rom)));
        assertTrue(err.toString().contains("reset vector"), err.toString());
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
        assertTrue(err.toString().contains("ROM"), err.toString());
    }
}
