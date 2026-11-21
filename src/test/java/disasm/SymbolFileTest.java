package disasm;

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
    void aMalformedSymbolLineIsAnErrorNamingFileAndLine() {
        for (String bad : List.of("0200 start", "0200 start label", "0200 start lable local", "0200 start label nonlocal",
                "0200 start label local extra", "123456789 big label local",
                "data 0200", "data 0200 0010 x", "data 10000 0001", "data 0200 0000", "data 0200 10001", "data xyz 0001")) {
            var e = assertThrows(SymbolFileException.class, () -> SymbolFile.parse("p.sym", "0100 ok label local\n" + bad + "\n"), bad);
            assertTrue(e.getMessage().startsWith("p.sym:2: error: "), e.getMessage());
        }
    }
}
