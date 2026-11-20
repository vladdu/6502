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
        Path p = dir.resolve("prog.bin");
        Files.write(p, bytes);
        return p.toString();
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
}
