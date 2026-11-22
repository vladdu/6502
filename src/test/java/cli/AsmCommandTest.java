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
        assertEquals("image 0200 0200 E5A7DC89\n0200 start label local\n", Files.readString(dir.resolve("foo.sym")));
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
    void anUnwritableSymbolFileLeavesTheExistingBinaryUntouched() throws IOException {
        Files.writeString(dir.resolve("foo.bin"), "old");
        Files.createDirectory(dir.resolve("foo.sym"));
        assertEquals(AsmCommand.ERRORS, run("asm", source(".defsection code, $0200\n.section code\nNOP\n").toString()));
        assertEquals("old", Files.readString(dir.resolve("foo.bin")));
        assertTrue(err.toString(UTF_8).contains("cannot write"), err.toString(UTF_8));
    }

    @Test
    void missingSourceFails() {
        assertNotEquals(0, run("asm", dir.resolve("nope.s").toString()));
        assertFalse(err.toString(UTF_8).isEmpty());
    }

    @Test
    void theGuidesTwoFileProgramRunsToTheExitDevice() throws IOException {
        Files.writeString(dir.resolve("lib.s"), guideExample("lib.s"));
        Path main = Files.writeString(dir.resolve("main.s"), guideExample("main.s"));
        assertEquals(0, run("asm", main.toString()), err.toString(UTF_8));
        Path bin = dir.resolve("main.bin");
        assertEquals(0, run("run", bin.toString()), err.toString(UTF_8));
        assertEquals("hi", out.toString(UTF_8));

        var bytes = Files.readAllBytes(bin);
        var hex = HexFormat.ofDelimiter(" ").withUpperCase();
        assertEquals(0x603, bytes.length); // $0200-$0802: the variables at $10 are not in it
        assertEquals("4C 10 02 A0 00 B1 10 F0 06 8D 00 D0 C8 D0 F6 60 A9 00 85 10 A9 08 85 11 20 03 02 A9 00 8D 00 D3",
                hex.formatHex(bytes, 0, 0x20));
        assertEquals("68 69 00", hex.formatHex(bytes, 0x600, 0x603));
    }

    /** The guide's one asm block that starts with {@code ; file}, read and not copied so that it can't rot. */
    private static String guideExample(String file) throws IOException {
        String guide = Files.readString(Path.of("docs/assembler.md"));
        String opening = "```asm\n; " + file + "\n";
        int at = guide.indexOf(opening);
        assertTrue(at >= 0 && at == guide.lastIndexOf(opening), "the guide needs one example " + file);
        int start = at + "```asm\n".length();
        return guide.substring(start, guide.indexOf("```", start));
    }
}
