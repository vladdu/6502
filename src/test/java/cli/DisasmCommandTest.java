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

    /** Assembles {@code src} to foo.bin and foo.sym, forgets what that printed, and returns the image's path. */
    private Path assemble(String src) throws IOException {
        Path source = Files.writeString(dir.resolve("foo.s"), src);
        assertEquals(0, run("asm", source.toString()), err.toString(UTF_8));
        err.reset();
        return dir.resolve("foo.bin");
    }

    @Test
    void theHelloDemoMovedTo0400RunsAndDisassemblesThereWithItsTextAsData() throws IOException {
        String hello = Files.readString(Path.of("demo/hello/hello.s")).replace("$0200", "$0400").replace("$0280", "$0480");
        assertTrue(hello.contains("$0400") && hello.contains("$0480"), hello);
        Path bin = assemble(hello);

        assertEquals(0, run("run", bin.toString()), err.toString(UTF_8));
        assertEquals("Hello, 6502!\n", out.toString(UTF_8));
        out.reset();
        assertEquals(0, run("disasm", bin.toString()), err.toString(UTF_8));

        String text = out.toString(UTF_8);
        assertTrue(text.startsWith(".defsection code, $0400\n"), text);
        assertTrue(text.contains("msg:\n        .byte \"Hello, 6502!\", $0A, $00 ; 0480 "), text);
    }

    @Test
    void aTypedAddressOtherThanTheImageOriginWarnsAndDoesNotShiftTheSymbols() throws IOException {
        Path bin = assemble(".defsection code, $0400\n.section code\nstart: JMP start\n");
        assertEquals(0, run("disasm", bin.toString(), "--org", "$0600"), err.toString(UTF_8));
        assertEquals(".defsection code, $0600\n.section code\nstart = $0400\n        JMP start               ; 0600  4C 00 04\n", out.toString(UTF_8));
        assertEquals("6502: using " + dir.resolve("foo.sym") + "\n6502: warning: " + bin
                + ": loaded at $0600, its Image origin is $0400; symbols are for $0400\n", err.toString(UTF_8));
    }

    @Test
    void aTyped0200IsATypedAddressAndNotTheDefault() throws IOException {
        Path bin = assemble(".defsection code, $0400\n.section code\nNOP\n");
        assertEquals(0, run("disasm", bin.toString(), "--org", "$0200"), err.toString(UTF_8));
        assertTrue(out.toString(UTF_8).startsWith(".defsection code, $0200\n"), out.toString(UTF_8));
        assertTrue(err.toString(UTF_8).contains("loaded at $0200, its Image origin is $0400"), err.toString(UTF_8));
    }

    @Test
    void anImageThatDoesNotMatchItsSiblingSymbolFileIsRefused() throws IOException {
        Path bin = assemble(".defsection code, $0400\n.section code\nstart: JMP start\n");
        Files.write(bin, new byte[] {(byte) 0xEA, (byte) 0xEA, (byte) 0xEA});
        assertEquals(DisasmCommand.ERRORS, run("disasm", bin.toString()));
        assertEquals("6502: " + dir.resolve("foo.sym") + " does not match " + bin + ": assemble again, remove it, or give --org\n",
                err.toString(UTF_8));
        assertEquals("", out.toString(UTF_8));
    }

    @Test
    void withATypedAddressAMismatchedSiblingIsIgnoredWholeAndNotShownAsUsed() throws IOException {
        Path bin = assemble(".defsection code, $0400\n.section code\nstart: .byte $4C, $00, $04\n");
        Files.write(bin, new byte[] {0x20, 0x00, 0x04});
        assertEquals(0, run("disasm", bin.toString(), "--org", "$0400"), err.toString(UTF_8));
        assertEquals(".defsection code, $0400\n.section code\n        JSR $0400               ; 0400  20 00 04\n", out.toString(UTF_8));
        assertEquals("6502: warning: " + dir.resolve("foo.sym") + " does not match " + bin + ": ignored\n", err.toString(UTF_8));
    }

    @Test
    void aNamedSymbolFilesForeignRecordIsDroppedWithAWarningKeepingItsSymbolsAndData() throws IOException {
        Path os = Files.writeString(dir.resolve("os.sym"), "image E000 E000 12345678\n0200 here label local\ndata 0200 0003\n");
        String app = image(3);
        assertEquals(0, run("disasm", app, "--syms", os.toString()), err.toString(UTF_8));
        String text = out.toString(UTF_8);
        assertTrue(text.startsWith(".defsection code, $0200\n.section code\nhere:\n        .byte $4C, $00, $02 "), text);
        assertEquals("6502: warning: " + os + " does not match " + app + ": using only its symbols and data ranges\n", err.toString(UTF_8));

        out.reset();
        assertEquals(0, run("disasm", app, "--syms", os.toString(), "--org", "$0300"), err.toString(UTF_8));
        assertTrue(out.toString(UTF_8).startsWith(".defsection code, $0300\n"), out.toString(UTF_8));
    }

    @Test
    void aNamedSymbolFilesMatchingRecordGivesTheAddress() throws IOException {
        Path bin = assemble(".defsection code, $0400\n.section code\nNOP\n");
        Path moved = Files.move(dir.resolve("foo.sym"), dir.resolve("named.sym"));
        assertEquals(0, run("disasm", bin.toString(), "--syms", moved.toString()), err.toString(UTF_8));
        assertTrue(out.toString(UTF_8).startsWith(".defsection code, $0400\n"), out.toString(UTF_8));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void anEntryPointOutsideTheImageFailsNamingTheFile() throws IOException {
        Path bin = assemble(".defsection code, $0400\n.section code\nNOP\n");
        Path sym = dir.resolve("foo.sym");
        Files.writeString(sym, "image 0400 0401 92DDE4EB\n");
        assertEquals(DisasmCommand.ERRORS, run("disasm", bin.toString()));
        assertEquals(sym + ":1: error: Entry point $0401 is outside the image\n", err.toString(UTF_8));
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
