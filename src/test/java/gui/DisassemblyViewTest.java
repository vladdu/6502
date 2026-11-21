package gui;

import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Disassembly pane's logic: the sticky window, re-anchoring, scrolling, marks and the commands its actions run. */
class DisassemblyViewTest {
    @TempDir Path dir;
    private final SnapshotBuilder b = new SnapshotBuilder();

    private SymbolTable symbols(String lines) throws Exception {
        var t = new SymbolTable();
        t.add(Files.writeString(dir.resolve("t.sym"), lines));
        return t;
    }

    private void poke(int at, int... bytes) {
        for (int i = 0; i < bytes.length; i++) b.memory[at + i] = (byte) bytes[i];
    }

    /** LDA #$01; STA $0300; JMP $0200 at $0200, BRKs after. */
    private void program() {
        poke(0x0200, 0xA9, 0x01, 0x8D, 0x00, 0x03, 0x4C, 0x00, 0x02);
    }

    private static DisassemblyView view(String expression, int rows) {
        var v = new DisassemblyView(expression);
        v.rows(rows);
        return v;
    }

    private static List<String> texts(DisassemblyView v) {
        return v.lines().stream().map(DisassemblyView.Row::text).toList();
    }

    @Test
    void whileTheFollowedAddressIsAVisibleInstructionStartOnlyTheHighlightMoves() {
        program();
        var v = view("pc", 4);
        v.display(b.build());
        assertEquals(0x0200, v.top());
        assertEquals(List.of("0200  A9 01     LDA #$01", "0202  8D 00 03  STA $0300", "0205  4C 00 02  JMP $0200", "0208  00        BRK"), texts(v));

        b.pc = 0x0205;
        v.display(b.build());
        assertEquals(0x0200, v.top());
        assertTrue(v.lines().get(2).pc());
        assertFalse(v.lines().get(0).pc());
    }

    @Test
    void withNoLabelInReachTheFollowedAddressBecomesTheTopLine() {
        program();
        var v = view("pc", 4);
        v.display(b.build());

        b.pc = 0x0203; // inside the STA
        v.display(b.build());
        assertEquals(0x0203, v.top());
        assertEquals("0203  00        BRK", v.lines().get(0).text());

        b.pc = 0x0300; // past the window
        v.display(b.build());
        assertEquals(0x0300, v.top());
    }

    @Test
    void reAnchoringSweepsFromTheNearestLabelWithTheFollowedAddressAsASyncPoint() throws Exception {
        program();
        b.symbols = symbols("0200 start label global\n");
        b.pc = 0x0203; // inside the STA
        var v = view("pc", 5);
        v.display(b.build());

        assertEquals(0x0200, v.top());
        assertEquals(List.of("0200            start:", "0200  A9 01     LDA #$01", "0202  8D        .byte $8D", "0203  00        BRK",
                "0204  03        .byte $03 ; undocumented"), texts(v));
        assertTrue(v.lines().get(3).pc());
    }

    @Test
    void aLabelOnlyReachesTheFollowedAddressFromLessThan100BytesBefore() throws Exception {
        b.pc = 0x0200;
        b.symbols = symbols("0100 far label global\n");
        var v = view("pc", 6);
        v.display(b.build());
        assertEquals(0x0200, v.top());

        b.symbols = symbols("0101 near label global\n");
        v = view("pc", 6);
        v.display(b.build());
        // swept from near, the followed line put a third of the way down since near's line would push it out of the window
        assertEquals(0x01FE, v.top());
        assertEquals(0x0200, v.lines().get(2).addr());
        assertTrue(v.lines().get(2).pc());
    }

    @Test
    void oneLineUpTakesTheEarliestStartWhoseDecodeEndsOnTheTopLine() {
        program();
        var v = view("$0205", 3);
        v.display(b.build());
        v.up();
        assertEquals(0x0202, v.top());
        assertFalse(v.following());

        poke(0x0300, 0x20, 0x00, 0x00, 0xEA); // at $0300 a JSR, at $0302 a BRK, both ending at $0303
        v.display(b.build());
        v.scroll(0x0303);
        v.up();
        assertEquals(0x0300, v.top());
    }

    @Test
    void oneLineUpWithNoStartEndingOnTheTopLineShowsTheByteAboveAsData() {
        poke(0x02FC, 0xA9, 0x8D, 0xA9, 0xEA); // each of $02FC-$02FE decodes past $02FF
        var v = view("$02FF", 3);
        v.display(b.build());
        v.up();
        assertEquals(0x02FE, v.top());
        assertEquals(List.of("02FE  A9        .byte $A9", "02FF  EA        NOP", "0300  00        BRK"), texts(v));
    }

    @Test
    void oneLineDownStartsAtTheNextInstruction() {
        program();
        var v = view("pc", 3);
        v.display(b.build());
        v.down();
        assertEquals(0x0202, v.top());
        assertFalse(v.following());
    }

    @Test
    void scrollingByHandDetachesAndFollowingAgainReattachesAtOnce() {
        program();
        var v = view("pc", 4);
        v.display(b.build());
        v.scroll(0x4000);
        assertFalse(v.following());

        b.pc = 0x0300;
        v.display(b.build());
        assertEquals(0x4000, v.top());
        v.following(true);
        assertEquals(0x0300, v.top());
    }

    @Test
    void goingToAnAddressAnchorsThereDetached() throws Exception {
        program();
        b.symbols = symbols("0200 start label global\n");
        var v = view("pc", 4);
        v.display(b.build());
        v.goTo(0x0205);
        assertFalse(v.following());
        assertEquals(0x0200, v.top());

        b.pc = 0x0300;
        v.display(b.build());
        assertEquals(0x0200, v.top());
    }

    @Test
    void anExpressionThatStopsResolvingKeepsItsLastGoodAddressWithTheError() throws Exception {
        program();
        b.symbols = symbols("0205 there label global\n");
        var v = view("there", 3);
        v.display(b.build());
        assertEquals(0x0205, v.top());
        b.symbols = new SymbolTable();
        v.display(b.build());
        assertEquals(0x0205, v.top());
        assertTrue(v.following());
        assertNotNull(v.error());
    }

    @Test
    void breakpointsAndThePreviousPcAreMarkedAndASymbolOperandHasItsValueAsTooltip() throws Exception {
        program();
        b.symbols = symbols("0300 buf label global\n");
        b.breakpoints = List.of(0x0205);
        b.previousPc = 0x0202;
        var v = view("pc", 3);
        v.display(b.build());

        var rows = v.lines();
        assertEquals("0202  8D 00 03  STA buf", rows.get(1).text());
        assertEquals(List.of(false, false, true), rows.stream().map(DisassemblyView.Row::breakpoint).toList());
        assertEquals(List.of(false, true, false), rows.stream().map(DisassemblyView.Row::previous).toList());
        assertEquals("$0300", rows.get(1).tooltip());
        assertNull(rows.get(0).tooltip());
        assertNull(rows.get(2).tooltip());
    }

    @Test
    void theGutterTogglesABreakpointAndTheMenuRunsUntilOrSetsPc() {
        b.breakpoints = List.of(0x0205);
        var v = view("pc", 3);
        v.display(b.build());
        assertEquals("b $0202", v.toggle(0x0202));
        assertEquals("b del $0205", v.toggle(0x0205));
        assertEquals("until $0202", DisassemblyView.runTo(0x0202));
        assertEquals("r pc=$0202", DisassemblyView.setPc(0x0202));
    }

    @Test
    void theOperandToShowIsItsBaseAddressOrThePointerForIndirectIndexed() {
        poke(0x0200, 0xA9, 0x01, 0x8D, 0x00, 0x03, 0xB1, 0x12, 0xBD, 0x00, 0x04, 0xA1, 0x20, 0xEA);
        var v = view("pc", 6);
        v.display(b.build());
        var rows = v.lines();
        assertNull(DisassemblyView.operand(rows.get(0))); // LDA #$01
        assertEquals("$0300", DisassemblyView.operand(rows.get(1)));
        assertEquals("($0012)", DisassemblyView.operand(rows.get(2))); // LDA ($12),Y
        assertEquals("$0400", DisassemblyView.operand(rows.get(3))); // LDA $0400,X
        assertEquals("$0020", DisassemblyView.operand(rows.get(4))); // LDA ($20,X)
        assertNull(DisassemblyView.operand(rows.get(5))); // NOP
    }

    @Test
    void jumpsCallsAndBranchesHaveATargetOthersNone() {
        poke(0x0200, 0x4C, 0x34, 0x12, 0x20, 0x00, 0x30, 0xF0, 0xFE, 0x6C, 0x00, 0x05, 0xEA);
        poke(0x0500, 0x78, 0x56);
        var v = view("pc", 5);
        v.display(b.build());
        var rows = v.lines();
        assertEquals(0x1234, v.target(rows.get(0)));
        assertEquals(0x3000, v.target(rows.get(1)));
        assertEquals(0x0206, v.target(rows.get(2))); // BEQ to itself
        assertEquals(0x5678, v.target(rows.get(3))); // JMP ($0500)
        assertEquals(-1, v.target(rows.get(4)));
    }
}
