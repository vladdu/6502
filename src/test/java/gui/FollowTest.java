package gui;

import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolTable;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Follow expressions, evaluated against snapshots. */
class FollowTest {
    @TempDir Path dir;
    private final SnapshotBuilder b = new SnapshotBuilder();

    private SymbolTable symbols(String lines) throws Exception {
        var t = new SymbolTable();
        t.add(Files.writeString(dir.resolve("t.sym"), lines));
        return t;
    }

    private int address(String expression) {
        var f = new Follow(expression);
        int a = f.address(b.build());
        assertNull(f.error(), expression);
        return a;
    }

    @Test
    void monitorValuesPcAndTheStackByteResolve() throws Exception {
        b.symbols = symbols("0300 buf label global\n");
        b.pc = 0x0812;
        b.s = 0xF0;

        assertEquals(0xC000, address("c000"));
        assertEquals(0xC000, address("$C000"));
        assertEquals(10, address("#10"));
        assertEquals(0x0300, address("buf"));
        assertEquals(0x0310, address("buf+10"));
        assertEquals(0x02FF, address("buf-1"));
        assertEquals(0x0812, address("pc"));
        assertEquals(0x01F0, address("s"));
    }

    @Test
    void aPointerIsTheLittleEndianWordPeekedAtItsExpressionWithPlainWrap() throws Exception {
        b.symbols = symbols("0010 ptr label global\n");
        b.memory[0x10] = 0x34;
        b.memory[0x11] = 0x12;
        b.memory[0x1234] = 0x78;
        b.memory[0x1235] = 0x56;
        b.memory[0xFFFF] = (byte) 0xCD;
        b.memory[0x0000] = (byte) 0xAB;

        assertEquals(0x1234, address("(ptr)"));
        assertEquals(0x5678, address("((ptr))"));
        assertEquals(0xABCD, address("(ffff)"));
    }

    @Test
    void anExpressionThatStopsResolvingKeepsItsLastGoodAddressAndSaysWhyUntilItResolvesAgain() throws Exception {
        var f = new Follow("buf+1");
        b.symbols = symbols("0300 buf label global\n");
        assertEquals(0x0301, f.address(b.build()));

        b.symbols = new SymbolTable(); // a Restart lost the symbol
        assertEquals(0x0301, f.address(b.build()));
        assertEquals("'buf+1' is not a number or symbol", f.error());

        b.symbols = symbols("0400 buf label global\n");
        assertEquals(0x0401, f.address(b.build()));
        assertNull(f.error());
    }

    @Test
    void aValueBeyond16BitsAndAnUnclosedPointerAreErrors() {
        var f = new Follow("10000");
        assertEquals(0, f.address(b.build()));
        assertEquals("10000: $10000 is not an address", f.error());
        f = new Follow("(10");
        f.address(b.build());
        assertEquals("'(10' is not a number or symbol", f.error());
    }
}
