package asm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler.Errors;
import asm.Assembler.Output;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AssemblerTest {
    private static Assembler.Result assemble(String src) {
        try {
            return Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Output ok(String src) {
        var r = assemble(src);
        if (r instanceof Errors(var messages)) fail(String.join("\n", messages));
        return (Output) r;
    }

    private static void assertBytes(String src, int... expected) {
        var bin = ok(src).binary();
        var actual = new int[bin.length];
        for (int i = 0; i < bin.length; i++) actual[i] = bin[i] & 0xFF;
        assertArrayEquals(expected, actual);
    }

    @Test
    void immediateLoad() {
        var out = ok("*= $0200\nLDA #$2A\n");
        assertEquals(0x0200, out.origin());
        assertBytes("*= $0200\nLDA #$2A\n", 0xA9, 0x2A);
    }

    private static String org(String body) {
        return "*= $0200\n" + body + "\n";
    }

    private static String errors(String src) {
        var r = assemble(src);
        if (r instanceof Errors(var messages)) return String.join("\n", messages);
        return fail("expected errors");
    }

    @Test
    void everyAddressingMode() {
        assertBytes(org("NOP"), 0xEA);
        assertBytes(org("ASL"), 0x0A);
        assertBytes(org("ASL A"), 0x0A);
        assertBytes(org("lsr a"), 0x4A);
        assertBytes(org("LDA #$12"), 0xA9, 0x12);
        assertBytes(org("LDA $12"), 0xA5, 0x12);
        assertBytes(org("LDA $12,X"), 0xB5, 0x12);
        assertBytes(org("LDX $12,Y"), 0xB6, 0x12);
        assertBytes(org("LDA $1234"), 0xAD, 0x34, 0x12);
        assertBytes(org("LDA $1234,X"), 0xBD, 0x34, 0x12);
        assertBytes(org("LDA $1234,Y"), 0xB9, 0x34, 0x12);
        assertBytes(org("JMP ($1234)"), 0x6C, 0x34, 0x12);
        assertBytes(org("LDA ($12,X)"), 0xA1, 0x12);
        assertBytes(org("LDA ($12),Y"), 0xB1, 0x12);
        assertBytes(org("BNE $0200"), 0xD0, 0xFE);
    }

    @Test
    void mnemonicsAndRegistersAreCaseInsensitive() {
        assertBytes(org("lda $12,x\nLdA ($12),y\nlDa ($12,X)"), 0xB5, 0x12, 0xB1, 0x12, 0xA1, 0x12);
    }

    @Test
    void literals() {
        assertBytes(org("LDA #255\nLDA #%1010\nLDA #'A'\nLDA #$ff"), 0xA9, 0xFF, 0xA9, 0x0A, 0xA9, 0x41, 0xA9, 0xFF);
    }

    @Test
    void zeroPageOnlyWhereTheMnemonicHasAZeroPageForm() {
        assertBytes(org("JMP $0012"), 0x4C, 0x12, 0x00);
        assertBytes(org("LDA $12,Y"), 0xB9, 0x12, 0x00);
        assertBytes(org("LDA $0012"), 0xA5, 0x12);
    }

    @Test
    void modePrefixesForceZeroPageOrAbsolute() {
        assertBytes(org("LDA a:$12"), 0xAD, 0x12, 0x00);
        assertBytes(org("LDA A:$12,X"), 0xBD, 0x12, 0x00);
        assertBytes(org("LDA z:zp\nzp = $34"), 0xA5, 0x34);
        assertTrue(errors(org("LDA z:$1234")).contains("does not fit in a byte"));
        assertTrue(errors(org("JMP z:$12")).contains("JMP has no zero page mode"));
    }

    @Test
    void forwardReferencesAssembleAbsolute() {
        assertBytes(org("LDA later\nJMP later\nlater: BRK"), 0xAD, 0x06, 0x02, 0x4C, 0x06, 0x02, 0x00);
        assertBytes(org("LDA zp+1,X\nzp = $10"), 0xBD, 0x11, 0x00);
        assertBytes(org("LDA #1+fwd\nfwd = 2"), 0xA9, 0x03);
        assertBytes(org("LDA (zp),Y\nzp = $10"), 0xB1, 0x10);
        assertBytes(org("JMP (vec-2)\nvec = $1236"), 0x6C, 0x34, 0x12);
        assertTrue(errors(org("STX fwd,Y\nfwd = $10")).contains("STX has no absolute,Y mode"));
        assertTrue(errors(org("STY fwd,X\nfwd = $10")).contains("STY has no absolute,X mode"));
        assertBytes(org("STX z:fwd,Y\nfwd = $10"), 0x96, 0x10);
    }

    @Test
    void branchesReachAcrossTheWrapAtFFFF() {
        assertBytes("*= $FFF0\nBNE $0005\n", 0xD0, 0x13);
        assertBytes("*= $0002\nBNE $FFF0\n", 0xD0, 0xEC);
        assertBytes("*= $FFF0\nBNE fwd\nfwd = $0005\n", 0xD0, 0x13);
    }

    @Test
    void labelsAndConstants() {
        assertBytes(org("""
                COUT = $D000
                start: LDA #'H'
                       STA COUT
                loop:  JMP loop
                       .word start, COUT+1
                       .byte 1, COUT-$CFFF, 'z'"""),
                0xA9, 0x48, 0x8D, 0x00, 0xD0, 0x4C, 0x05, 0x02, 0x00, 0x02, 0x01, 0xD0, 0x01, 0x01, 0x7A);
    }

    @Test
    void symbolsAreCaseSensitive() {
        assertBytes(org("v = 1\nV = 2\nLDA #v\nLDA #V"), 0xA9, 0x01, 0xA9, 0x02);
    }

    @Test
    void starReadsThePcAndStarEqualsOpensASection() {
        var out = ok("*= $0300\nhere = *\nJMP *\n*= $0310\n.word here, *");
        assertEquals(0x0300, out.origin());
        var b = out.binary();
        assertEquals(0x14, b.length);
        assertEquals(0x4C, b[0] & 0xFF);
        assertEquals(0x00, b[1] & 0xFF);
        assertEquals(0x03, b[2] & 0xFF);
        assertEquals(0x00, b[3]); // gap filled with $00
        assertEquals(0x00, b[0x10] & 0xFF);
        assertEquals(0x03, b[0x11] & 0xFF);
        assertEquals(0x12, b[0x12] & 0xFF);
        assertEquals(0x03, b[0x13] & 0xFF);
    }

    @Test
    void binarySpansLowestToHighestAddressWithGapsZeroed() {
        assertBytes("*= $0205\n.byte 5\n*= $0200\n.byte 1\n", 0x01, 0, 0, 0, 0, 0x05);
        assertEquals(0x0200, ok("*= $0205\n.byte 5\n*= $0200\n.byte 1\n").origin());
    }

    @Test
    void branches() {
        assertBytes(org("loop: DEX\nBNE loop\nBEQ done\nNOP\ndone: RTS"), 0xCA, 0xD0, 0xFD, 0xF0, 0x01, 0xEA, 0x60);
        assertBytes(org("BCC *+129"), 0x90, 0x7F);
        assertBytes(org("BCC *-126"), 0x90, 0x80);
    }

    @Test
    void branchesOutOfRangeAreRejected() {
        assertTrue(errors(org("BCC *+130")).contains("branch target out of range (128 bytes)"));
        assertTrue(errors(org("BCC *-127")).contains("branch target out of range (-129 bytes)"));
        assertTrue(errors(org("BEQ far\n*= $0300\nfar: RTS")).contains("branch target out of range"));
    }

    @Test
    void constantsMustBeResolvedAtDefinition() {
        assertTrue(errors(org("x = later\nlater = 1")).contains("define later before this use"));
    }

    @Test
    void symbolsCannotBeRedefined() {
        assertTrue(errors(org("x = 1\nx = 2")).contains("x is already defined"));
        assertTrue(errors(org("l: NOP\nl: NOP")).contains("l is already defined"));
    }

    @Test
    void emittingBeforeTheFirstOriginIsAnError() {
        assertTrue(errors("LDA #1\n").contains("no origin yet"));
        assertTrue(errors(".byte 1\n").contains("no origin yet"));
    }

    @Test
    void errorHasLocationSourceLineAndCaret() {
        assertEquals("t.s:2:5: error: value $1FF does not fit in a byte\nLDA #$1FF\n    ^", errors(org("LDA #$1FF")));
        assertEquals("t.s:2:2: error: unknown instruction 'FOO'\n\tFOO\n\t^", errors(org("\tFOO")));
    }

    @Test
    void everyErrorOfTheRunIsReported() {
        var r = (Errors) assemble(org("FOO\nLDA #$100\nJMP nowhere\nNOP"));
        assertEquals(3, r.messages().size());
        assertTrue(r.messages().get(0).startsWith("t.s:2:1: error: unknown instruction"));
        assertTrue(r.messages().get(1).startsWith("t.s:3:5: error: value $100"));
        assertTrue(r.messages().get(2).startsWith("t.s:4:5: error: undefined symbol nowhere"));
    }

    @Test
    void listingShowsAddressBytesAndSourceWithLongDataWrapped() {
        var out = ok("""
                FOO = 1
                *= $0200
                ; hi
                start:  LDA #$2A
                        STA $D300
                        .word start, start
                """);
        assertEquals("""
                                FOO = 1
                0200            *= $0200
                0200            ; hi
                0200  A9 2A     start:  LDA #$2A
                0202  8D 00 D3          STA $D300
                0205  00 02 00          .word start, start
                0208  02
                """, out.listing());
    }

    @Test
    void listingShowsResolvedForwardReferences() {
        assertEquals("0200            *= $0200\n0200  4C 03 02  JMP l\n0203  60        l: RTS\n",
                ok(org("JMP l\nl: RTS")).listing());
    }

    @Test
    void symbolFileSortedByValue() {
        var out = ok("""
                *= $0200
                COUT = $D000
                ZERO = 0
                start: NOP
                end: RTS
                """);
        assertEquals("0000 ZERO const local\n0200 start label local\n0201 end label local\nD000 COUT const local\n",
                out.symbols());
    }
}
