package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsmCommandTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.execute(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
    }

    private Path source(String text) throws IOException {
        return Files.writeString(dir.resolve("foo.s"), text);
    }

    @Test
    void writesBinaryListingAndSymbolsNextToTheSource() throws IOException {
        assertEquals(0, run("asm", source(".defsection code, $0200\n.section code\nstart: LDA #$2A\n").toString()), err.toString(UTF_8));
        assertArrayEquals(new byte[] {(byte) 0xA9, 0x2A}, Files.readAllBytes(dir.resolve("foo.bin")));
        assertEquals("                .defsection code, $0200\n0200            .section code\n0200  A9 2A     start: LDA #$2A\n", Files.readString(dir.resolve("foo.lst")));
        assertEquals("0200 start label local\n", Files.readString(dir.resolve("foo.sym")));
    }

    @Test
    void outputBaseOption() throws IOException {
        Path base = dir.resolve("out/prog");
        Files.createDirectories(dir.resolve("out"));
        assertEquals(0, run("asm", source(".defsection code, $0200\n.section code\nNOP\n").toString(), "-o", base.toString()), err.toString(UTF_8));
        assertTrue(Files.exists(dir.resolve("out/prog.bin")));
        assertTrue(Files.exists(dir.resolve("out/prog.lst")));
        assertTrue(Files.exists(dir.resolve("out/prog.sym")));
        assertFalse(Files.exists(dir.resolve("foo.bin")));
    }

    @Test
    void onErrorWritesNothingAndLeavesExistingFilesUntouched() throws IOException {
        Files.writeString(dir.resolve("foo.bin"), "old");
        Path src = source(".defsection code, $0200\n.section code\nFOO\nLDA #$100\n");
        assertNotEquals(0, run("asm", src.toString()));
        assertEquals("old", Files.readString(dir.resolve("foo.bin")));
        assertFalse(Files.exists(dir.resolve("foo.lst")));
        assertFalse(Files.exists(dir.resolve("foo.sym")));
        assertTrue(err.toString(UTF_8).contains(src + ":3:1: error: unknown instruction 'FOO'\nFOO\n^"), err.toString(UTF_8));
        assertTrue(err.toString(UTF_8).contains(src + ":4:5: error: value $100"), err.toString(UTF_8));
    }

    @Test
    void missingSourceFails() {
        assertNotEquals(0, run("asm", dir.resolve("nope.s").toString()));
        assertFalse(err.toString(UTF_8).isEmpty());
    }

    @Test
    void assembledProgramRunsToTheExitDevice() throws IOException {
        Files.writeString(dir.resolve("lib.s"), """
                        .section code
                get:    LDA table,X
                        RTS
                        .section data
                table:  .byte 0, 42
                """);
        Path src = source("""
                EXIT = $D300
                        .defsection code, $0200
                        .defsection data, $0210
                        .section code
                        JMP main
                        .include "lib.s"
                main:   LDX index
                        JSR get
                        STA EXIT
                        .section data
                index:  .byte 1
                """);
        assertEquals(0, run("asm", src.toString()), err.toString(UTF_8));
        assertEquals("4C 07 02 BD 10 02 60 AE 12 02 20 03 02 8D 00 D3 00 2A 01",
                HexFormat.ofDelimiter(" ").withUpperCase().formatHex(Files.readAllBytes(dir.resolve("foo.bin"))));
        assertEquals(42, run("run", dir.resolve("foo.bin").toString()));
    }
}
