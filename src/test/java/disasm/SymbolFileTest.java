package disasm;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolFile.Data;
import disasm.SymbolFile.Symbol;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolFileTest {
    @Test
    void readsTheAssemblersSymbolFileInFileOrder() throws SymbolFileException {
        assertEquals(List.of(
                        new Symbol(0x000D, "CR", false),
                        new Symbol(0x0200, "start", true),
                        new Symbol(0x0204, "start@loop", true),
                        new Symbol(-2, "neg", false)),
                SymbolFile.parse("p.sym", """
                        ; symbols
                        000D CR const local

                        0200 start label global ; entry
                        0204 start@loop label local
                        FFFFFFFE neg const local
                        """).symbols());
    }

    @Test
    void anUnreadableFileIsAnErrorNamingIt() {
        Path missing = Path.of("no/such.sym");
        var e = assertThrows(SymbolFileException.class, () -> SymbolFile.read(missing));
        assertTrue(e.getMessage().startsWith("cannot read " + missing + ": "), e.getMessage());
    }

    @Test
    void linesOfUnknownKindAreSkipped() throws SymbolFileException {
        assertEquals(List.of(new Symbol(0x0200, "start", true)),
                SymbolFile.parse("p.sym", "code 0200 0210\n0200 start label local\n").symbols());
    }

    @Test
    void readsDataRangesInFileOrder() throws SymbolFileException {
        var c = SymbolFile.parse("p.sym", "0200 start label local\ndata 021E 000E ; msg\ndata 0000 10000\n");
        assertEquals(List.of(new Symbol(0x0200, "start", true)), c.symbols());
        assertEquals(List.of(new Data(0x021E, 0x0E), new Data(0, 0x10000)), c.data());
        assertTrue(new Data(0xFFFF, 2).contains(0x10000)); // wraps
        assertFalse(new Data(0x021E, 0x0E).contains(0x022C));
    }

    @Test
    void readsTheImageLineWithAddressesOfOneToFourHexDigits() throws SymbolFileException {
        var image = SymbolFile.parse("p.sym", "; by hand\nimage 80 8a cbf43926 ; origin, Entry point, CRC-32\n").imageLine();
        assertEquals(0x80, image.origin());
        assertEquals(0x8A, image.entryPoint());
        assertEquals(2, image.line());
        assertTrue(image.matches("123456789".getBytes(US_ASCII))); // the CRC-32 check value
        assertFalse(image.matches("123456780".getBytes(US_ASCII)));
        assertNull(SymbolFile.parse("p.sym", "0200 start label local\n").imageLine());
    }

    @Test
    void aMalformedOrSecondImageLineIsAnErrorNamingFileAndLine() {
        for (String bad : List.of("image", "image 0400 0403", "image 0400 0403 9A3C11F0 x", "image 10000 0403 9A3C11F0",
                "image 0400 10000 9A3C11F0", "image xyz 0403 9A3C11F0", "image 0400 0403 9A3C11F", "image 0400 0403 09A3C11F0")) {
            var e = assertThrows(SymbolFileException.class, () -> SymbolFile.parse("p.sym", "0100 ok label local\n" + bad + "\n"), bad);
            assertTrue(e.getMessage().startsWith("p.sym:2: error: "), e.getMessage());
        }
        String line = "image 0400 0403 9A3C11F0\n";
        var e = assertThrows(SymbolFileException.class, () -> SymbolFile.parse("p.sym", line + line));
        assertEquals("p.sym:2: error: a second image line", e.getMessage());
    }

    @Test
    void aMalformedSymbolLineIsAnErrorNamingFileAndLine() {
        for (String bad : List.of("0200 start", "0200 start label", "0200 start lable local", "0200 start label nonlocal",
                "0200 start label local extra", "123456789 big label local",
                "data 0200", "data 0200 0010 x", "data 10000 0001", "data 0200 0000", "data 0200 10001", "data xyz 0001")) {
            var e = assertThrows(SymbolFileException.class, () -> SymbolFile.parse("p.sym", "0100 ok label local\n" + bad + "\n"), bad);
            assertTrue(e.getMessage().startsWith("p.sym:2: error: "), e.getMessage());
        }
    }
}
