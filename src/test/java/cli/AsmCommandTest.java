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

class AsmCommandTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.execute(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true), new PrintStream(err, true));
    }

    private Path source(String text) throws IOException {
        return Files.writeString(dir.resolve("foo.s"), text);
    }

    @Test
    void writesBinaryListingAndSymbolsNextToTheSource() throws IOException {
        assertEquals(0, run("asm", source("*= $0200\nstart: LDA #$2A\n").toString()), err.toString());
        assertArrayEquals(new byte[] {(byte) 0xA9, 0x2A}, Files.readAllBytes(dir.resolve("foo.bin")));
        assertEquals("0200            *= $0200\n0200  A9 2A     start: LDA #$2A\n", Files.readString(dir.resolve("foo.lst")));
        assertEquals("0200 start label local\n", Files.readString(dir.resolve("foo.sym")));
    }

    @Test
    void outputBaseOption() throws IOException {
        Path base = dir.resolve("out/prog");
        Files.createDirectories(base.getParent());
        assertEquals(0, run("asm", source("*= $0200\nNOP\n").toString(), "-o", base.toString()), err.toString());
        assertTrue(Files.exists(dir.resolve("out/prog.bin")));
        assertTrue(Files.exists(dir.resolve("out/prog.lst")));
        assertTrue(Files.exists(dir.resolve("out/prog.sym")));
        assertFalse(Files.exists(dir.resolve("foo.bin")));
    }

    @Test
    void onErrorWritesNothingAndLeavesExistingFilesUntouched() throws IOException {
        Files.writeString(dir.resolve("foo.bin"), "old");
        Path src = source("*= $0200\nFOO\nLDA #$100\n");
        assertNotEquals(0, run("asm", src.toString()));
        assertEquals("old", Files.readString(dir.resolve("foo.bin")));
        assertFalse(Files.exists(dir.resolve("foo.lst")));
        assertFalse(Files.exists(dir.resolve("foo.sym")));
        assertTrue(err.toString().contains(src + ":2:1: error: unknown instruction 'FOO'\nFOO\n^"), err.toString());
        assertTrue(err.toString().contains(src + ":3:5: error: value $100"), err.toString());
    }

    @Test
    void missingSourceFails() {
        assertNotEquals(0, run("asm", dir.resolve("nope.s").toString()));
        assertFalse(err.toString().isEmpty());
    }

    @Test
    void assembledProgramRunsToTheExitDevice() throws IOException {
        Path src = source("""
                EXIT = $D300
                        *= $0200
                start:  JMP main
                table:  .byte 0, 42
                main:   LDX #1
                        LDA table,X
                        STA EXIT
                """);
        assertEquals(0, run("asm", src.toString()), err.toString());
        assertEquals(42, run("run", dir.resolve("foo.bin").toString()));
    }
}
