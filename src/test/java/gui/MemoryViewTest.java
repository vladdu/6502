package gui;

import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import machine.Machine.Area.Kind;
import machine.Watchpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A Memory view's logic: rows, following and detaching, change highlights, tints, marks and the commands its actions run. */
class MemoryViewTest {
    @TempDir Path dir;
    private final SnapshotBuilder b = new SnapshotBuilder();

    @Test
    void theFollowedAddressesRowIsTheTopRow() {
        b.s = 0xFD;
        var v = new MemoryView("0213");
        v.display(b.build());
        assertEquals(0x0210, v.top());
        var stack = new MemoryView("s");
        stack.display(b.build());
        assertEquals(0x01F0, stack.top());
    }

    @Test
    void scrollingByHandDetachesAndTheFollowingToggleReattachesAtOnce() {
        var v = new MemoryView("pc");
        v.display(b.build());
        v.scroll(0x4008);
        assertFalse(v.following());
        assertEquals(0x4000, v.top());

        b.pc = 0x0300;
        v.display(b.build());
        assertEquals(0x4000, v.top());
        v.following(true);
        assertTrue(v.following());
        assertEquals(0x0300, v.top());
    }

    @Test
    void aNewExpressionAttachesAndMovesAtOnce() {
        var v = new MemoryView("pc");
        v.display(b.build());
        v.scroll(0x4000);
        v.follow("$D000");
        assertTrue(v.following());
        assertEquals("$D000", v.expression());
        assertEquals(0xD000, v.top());
    }

    @Test
    void anExpressionThatStopsResolvingStaysAttachedAtItsLastGoodAddressWithTheError() throws Exception {
        var v = new MemoryView("buf");
        b.symbols = new SymbolTable();
        b.symbols.add(Files.writeString(dir.resolve("t.sym"), "0300 buf label global\n"));
        v.display(b.build());
        b.symbols = new SymbolTable();
        v.display(b.build());
        assertEquals(0x0300, v.top());
        assertTrue(v.following());
        assertEquals("'buf' is not a number or symbol", v.error());
    }

    @Test
    void runningChangesAreAgainstThePreviousSnapshot() {
        var v = new MemoryView("0");
        b.running = true;
        b.memory[1] = 1;
        v.display(b.build());
        assertFalse(v.cell(1).changed()); // nothing before the first

        b.memory = b.memory.clone();
        b.memory[2] = 2;
        b.baselineMemory = new byte[0x10000]; // the pre-run memory, which a running view ignores
        v.display(b.build());
        assertFalse(v.cell(1).changed());
        assertTrue(v.cell(2).changed());
    }

    @Test
    void stoppedChangesAreAgainstTheSnapshotJustBeforeTheLastRun() {
        var v = new MemoryView("0");
        b.baselineMemory = new byte[0x10000];
        b.memory[1] = 1;
        v.display(b.build());
        b.memory = b.memory.clone();
        b.memory[2] = 2; // an m = edit after the run
        v.display(b.build());
        assertTrue(v.cell(1).changed());
        assertTrue(v.cell(2).changed());
        assertFalse(v.cell(3).changed());
    }

    @Test
    void eachByteIsTintedByItsPagesKindInTheMemoryMap() {
        var v = new MemoryView("0");
        v.display(b.build());
        assertEquals(Kind.RAM, v.cell(0x0000).kind());
        assertEquals(Kind.RAM, v.cell(0xC7FF).kind());
        assertEquals(Kind.ROM, v.cell(0xC800).kind());
        assertEquals(Kind.DEVICE, v.cell(0xD000).kind());
        assertEquals(Kind.DEVICE, v.cell(0xD3FF).kind());
        assertEquals(Kind.UNMAPPED, v.cell(0xD400).kind());
        assertEquals(Kind.ROM, v.cell(0xFFFF).kind());
    }

    @Test
    void thePcAndStackBytesAreMarkedAndSoAreWatchpointRanges() {
        b.pc = 0x0812;
        b.s = 0xF0;
        b.watchpoints = List.of(new Watchpoint(0x0300, 4, false, true));
        var v = new MemoryView("0");
        v.display(b.build());
        assertTrue(v.cell(0x0812).pc());
        assertFalse(v.cell(0x0813).pc());
        assertTrue(v.cell(0x01F0).stack());
        assertFalse(v.cell(0x00F0).stack());
        assertFalse(v.cell(0x02FF).watched());
        assertTrue(v.cell(0x0300).watched());
        assertTrue(v.cell(0x0303).watched());
        assertFalse(v.cell(0x0304).watched());
    }

    @Test
    void bytesShowAsMShowsThemWithAnAddressAndLabelTooltip() throws Exception {
        b.symbols.add(Files.writeString(dir.resolve("t.sym"), "0300 buf label global\n"));
        b.memory[0x0300] = 'H';
        b.memory[0x0301] = 'i';
        b.memory[0x0302] = 0x7F;
        b.memory[0x0303] = (byte) 0xFF;
        var v = new MemoryView("0");
        v.display(b.build());
        assertEquals(0xFF, v.cell(0x0303).value());
        assertEquals("Hi..............", v.ascii(0x0300));
        assertEquals("$0300  buf", v.tooltip(0x0300));
        assertEquals("$0301", v.tooltip(0x0301));
    }

    @Test
    void typingHexOverAByteIsExactlyAMemoryWrite() {
        assertEquals("m $0300 = $4C", MemoryView.edit(0x0300, "4c"));
        assertEquals("m $FFFF = $07", MemoryView.edit(0xFFFF, " 7 "));
        assertNull(MemoryView.edit(0x0300, "4g"));
        assertNull(MemoryView.edit(0x0300, "123"));
        assertNull(MemoryView.edit(0x0300, ""));
    }

    @Test
    void watchingASelectionIsAWatchpointCommandOverItsBytes() {
        assertEquals("w $0300 $10", MemoryView.watch(false, true, 0x0300, 0x030F));
        assertEquals("w r $0300 $1", MemoryView.watch(true, false, 0x0300, 0x0300));
        assertEquals("w rw $FFF0 $10", MemoryView.watch(true, true, 0xFFF0, 0xFFFF));
    }
}
