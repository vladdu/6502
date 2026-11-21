package disasm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolFile.Symbol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolTableTest {
    @TempDir Path dir;
    private final SymbolTable t = new SymbolTable();

    private Path sym(String name, String text) throws Exception {
        return Files.writeString(dir.resolve(name), text, UTF_8);
    }

    @Test
    void aNameInTwoFilesLooksUpTheLaterButBothStillNameTheirAddresses() throws Exception {
        t.add(sym("rom.sym", "E000 loop label local\n"));
        t.add(sym("prog.sym", "0200 loop label local\n"));

        assertEquals(0x0200, t.value("loop"));
        assertEquals("loop", t.label(0xE000));
        assertEquals("loop", t.label(0x0200));
        assertNull(t.value("nope"));
    }

    @Test
    void theFirstLoadedLabelNamesASharedAddressAndConstsNeverDo() throws Exception {
        t.add(sym("a.sym", "0300 size const local\n0300 first label local\n"));
        t.add(sym("b.sym", "0300 second label local\n"));

        assertEquals("first", t.label(0x0300));
        assertNull(t.label(0x0400));
    }

    @Test
    void filesAreKeptInOrderAndAMalformedOrMissingFileAddsNothing() throws Exception {
        Path a = sym("a.sym", "0200 a label local\n");
        t.add(a);
        Path bad = sym("bad.sym", "0400 b label local\n0500 c nolabel local\n");

        assertThrows(SymbolFileException.class, () -> t.add(bad));
        t.addSiblingOf(dir.resolve("none.bin")); // no none.sym: nothing to add
        Path prog = sym("prog.sym", "0600 p label local\n");
        t.addSiblingOf(dir.resolve("prog.bin"));

        assertNull(t.value("b"));
        assertEquals(List.of(a.toString(), prog.toString()), t.files());
        assertEquals(List.of("a", "p"), t.symbols().stream().map(Symbol::name).toList());
    }
}
