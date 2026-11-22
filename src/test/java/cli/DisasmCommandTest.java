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
import org.junit.jupiter.api.io.TempDir;

class DisasmCommandTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.execute(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
    }

    private String image(int size) throws IOException {
        var b = new byte[size];
        b[0] = (byte) 0x4C; // JMP $0200
        b[2] = 0x02;
        return Files.write(dir.resolve("prog.bin"), b).toString();
    }

    @Test
    void printsSourceAtTheDefaultOrigin() throws IOException {
        assertEquals(0, run("disasm", image(3)), err.toString(UTF_8));
        assertEquals(".defsection code, $0200\n.section code\n        JMP $0200               ; 0200  4C 00 02\n", out.toString(UTF_8));
    }

    @Test
    void originAndSymbols() throws IOException {
        Path syms = Files.writeString(dir.resolve("prog.sym"), "0200 here label local\n");
        assertEquals(0, run("disasm", image(3), "--org", "0x300", "--syms", syms.toString()), err.toString(UTF_8));
        assertEquals(".defsection code, $0300\n.section code\nhere = $0200\n        JMP here                ; 0300  4C 00 02\n", out.toString(UTF_8));
    }

    @Test
    void assemblerOutputDisassemblesWithItsSymbols() throws IOException {
        Path src = Files.writeString(dir.resolve("foo.s"), ".defsection code, $0200\n.section code\nstart: JMP start\n");
        assertEquals(0, run("asm", src.toString()), err.toString(UTF_8));
        assertEquals(0, run("disasm", dir.resolve("foo.bin").toString(), "--syms", dir.resolve("foo.sym").toString()), err.toString(UTF_8));
        assertEquals(".defsection code, $0200\n.section code\nstart:\n        JMP start               ; 0200  4C 00 02\n", out.toString(UTF_8));
    }

    @Test
    void siblingSymbolFileLoadsByItself() throws IOException {
        Path src = Files.writeString(dir.resolve("foo.s"), ".defsection code, $0200\n.section code\nstart: JMP start\n");
        assertEquals(0, run("asm", src.toString()), err.toString(UTF_8));
        err.reset();
        assertEquals(0, run("disasm", dir.resolve("foo.bin").toString()), err.toString(UTF_8));
        assertEquals(".defsection code, $0200\n.section code\nstart:\n        JMP start               ; 0200  4C 00 02\n", out.toString(UTF_8));
        assertEquals("6502: using " + dir.resolve("foo.sym") + "\n", err.toString(UTF_8));
    }

    @Test
    void explicitSymsReplacesTheSibling() throws IOException {
        Files.writeString(dir.resolve("prog.sym"), "0200 sibling label local\n");
        Path syms = Files.writeString(dir.resolve("other.sym"), "0200 other label local\n");
        assertEquals(0, run("disasm", image(3), "--syms", syms.toString()), err.toString(UTF_8));
        assertEquals(".defsection code, $0200\n.section code\nother:\n        JMP other               ; 0200  4C 00 02\n", out.toString(UTF_8));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void malformedSymbolLineFailsWithFileAndLine() throws IOException {
        Path syms = Files.writeString(dir.resolve("bad.sym"), "; ok\n0200 here label\n");
        assertNotEquals(0, run("disasm", image(3), "--syms", syms.toString()));
        assertTrue(err.toString(UTF_8).startsWith(syms + ":2: error: "), err.toString(UTF_8));
        assertEquals("", out.toString(UTF_8));
    }

    @Test
    void imageEndingPastTopOfMemoryIsAnError() throws IOException {
        String img = image(0x100);
        assertEquals(0, run("disasm", img, "--org", "$FF00"), err.toString(UTF_8));
        out.reset();
        assertNotEquals(0, run("disasm", img, "--org", "$FF01"));
        assertTrue(err.toString(UTF_8).contains("$FF01"), err.toString(UTF_8));
        assertEquals("", out.toString(UTF_8));
    }

    @Test
    void missingImageFails() {
        assertNotEquals(0, run("disasm", dir.resolve("nope.bin").toString()));
        assertFalse(err.toString(UTF_8).isEmpty());
    }
}
