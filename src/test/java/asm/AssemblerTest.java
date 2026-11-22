package asm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler.Errors;
import asm.Assembler.Output;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssemblerTest {
    private static Assembler.Result assemble(String src) {
        try {
            return Assembler.assemble(Path.of("t.s"), _ -> src.getBytes(UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Output ok(String src) {
        return ok(assemble(src));
    }

    private static Output ok(Assembler.Result r) {
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
        var out = ok(org("LDA #$2A"));
        assertEquals(0x0200, out.origin());
        assertBytes(org("LDA #$2A"), 0xA9, 0x2A);
    }

    private static String org(String body) {
        return org("$0200", body);
    }

    /** {@code body} from line 3 on, in a Section code at {@code origin}. */
    private static String org(String origin, String body) {
        return ".defsection code, " + origin + "\n.section code\n" + body + "\n";
    }

    @Test
    void theSymbolFileStartsWithTheImageOriginEntryPointAndChecksum() {
        var out = ok("""
                        .defsection high, $0404
                        .defsection vars, $10, reserved
                        .defsection none, $0100
                        .defsection low,  $0400
                        .section vars
                v:      .res 2
                        .section high
                        .byte "56789"
                        .section low
                        .byte "1234"
                """);
        assertTrue(out.symbols().startsWith("image 0400 0400 CBF43926\n"), out.symbols()); // CBF43926 checks "123456789"
    }

    @Test
    void anImageWithNoBytesGetsNoImageLine() {
        assertEquals("", ok(".defsection code, $0400\n").symbols());
        assertEquals("0010 v label local\n", ok(".defsection vars, $10, reserved\n.section vars\nv: .res 2\n").symbols());
    }

    /** The Symbol file's text after its image line, which has its own tests. */
    private static String symbolLines(Output out) {
        return out.symbols().replaceFirst("^image .*\n", "");
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
    void forwardReferencesOutsideZeroPageAssembleAbsolute() {
        assertBytes(org("LDA later\nJMP later\nlater: BRK"), 0xAD, 0x06, 0x02, 0x4C, 0x06, 0x02, 0x00);
        assertBytes(org("LDA abs+1,X\nabs = $FF"), 0xBD, 0x00, 0x01);
        assertBytes(org("LDA neg-1\nneg = 0"), 0xAD, 0xFF, 0xFF);
        assertBytes(org("LDA #1+fwd\nfwd = 2"), 0xA9, 0x03);
        assertBytes(org("LDA (zp),Y\nzp = $10"), 0xB1, 0x10);
        assertBytes(org("JMP (vec-2)\nvec = $1236"), 0x6C, 0x34, 0x12);
        assertTrue(errors(org("STX fwd,Y\nfwd = $10")).contains("STX has no absolute,Y mode"));
        assertTrue(errors(org("STY fwd,X\nfwd = $10")).contains("STY has no absolute,X mode"));
        assertBytes(org("STX z:fwd,Y\nfwd = $10"), 0x96, 0x10);
    }

    @Test
    void forwardOperandInZeroPageIsAnErrorThatNamesTheFixes() {
        assertEquals("t.s:3:5: error: operand $10 is in zero page: define ptr before this use, or write z: or a:\nsta ptr\n    ^",
                errors(org("sta ptr\nptr = $10")));
    }

    @Test
    void everyKindOfForwardSymbolInZeroPageIsAnError() {
        String defineFirst = " before this use, or write z: or a:";
        assertTrue(errors(org("sta src\nptr = $10\nsrc = ptr")).contains("operand $10 is in zero page: define src" + defineFirst));
        assertTrue(errors(org("sta K\nK = $FB")).contains("operand $FB is in zero page: define K" + defineFirst));
        assertTrue(errors(org("sta var\n.defsection low, 0\n.section low\nvar:")).contains("operand $0 is in zero page: define var" + defineFirst));
        assertTrue(errors(org("lda top\ntop = $FF")).contains("operand $FF is in zero page: define top" + defineFirst));
        assertTrue(errors(org("lda zp+1,X\nzp = $10")).contains("operand $11 is in zero page: define zp" + defineFirst));
        assertTrue(errors(org("ldx ptr,y\nptr = $10")).contains("operand $10 is in zero page: define ptr" + defineFirst));
    }

    @Test
    void forwardLowOrHighByteAsAnAddressIsAnError() {
        assertTrue(errors(org("lda <fwd\nfwd = $1234")).contains("operand $34 is in zero page: define fwd before this use"));
        assertTrue(errors(org("lda >fwd,x\nfwd = $1234")).contains("operand $12 is in zero page: define fwd before this use"));
    }

    @Test
    void everyForwardOperandInZeroPageIsReportedInOneRun() {
        var r = (Errors) assemble(org("sta ptr\nlda #1\nstx ptr+1\nlda nowhere\nptr = $10"));
        assertEquals(3, r.messages().size());
        assertTrue(r.messages().get(0).startsWith("t.s:3:5: error: operand $10 is in zero page"));
        assertTrue(r.messages().get(1).startsWith("t.s:5:5: error: operand $11 is in zero page"));
        assertTrue(r.messages().get(2).startsWith("t.s:6:5: error: undefined symbol nowhere"));
    }

    @Test
    void forwardZeroPageIsAllowedWhereTheSizeIsNotInDoubt() {
        String ptr = "\nptr = $10";
        assertBytes(org("sta z:ptr" + ptr), 0x85, 0x10);
        assertBytes(org("sta a:ptr" + ptr), 0x8D, 0x10, 0x00);
        assertBytes(org("lda (ptr),y" + ptr), 0xB1, 0x10);
        assertBytes(org("lda (ptr,x)" + ptr), 0xA1, 0x10);
        assertBytes(org("jmp ptr" + ptr), 0x4C, 0x10, 0x00);
        assertBytes(org("jmp (ptr)" + ptr), 0x6C, 0x10, 0x00);
        assertBytes(org("lda ptr,y" + ptr), 0xB9, 0x10, 0x00);
        assertBytes(org("lda #ptr" + ptr), 0xA9, 0x10);
        assertBytes(org(".byte ptr\n.word ptr" + ptr), 0x10, 0x10, 0x00);
        assertBytes(org("$0000", "bne ptr" + ptr), 0xD0, 0x0E);
    }

    @Test
    void branchesReachAcrossTheWrapAtFFFF() {
        assertBytes(org("$FFF0", "BNE $0005"), 0xD0, 0x13);
        assertBytes(org("$0002", "BNE $FFF0"), 0xD0, 0xEC);
        assertBytes(org("$FFF0", "BNE fwd\nfwd = $0005"), 0xD0, 0x13);
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
    void starReadsThePcOfTheCurrentSection() {
        var out = ok(org("$0300", "here = *\nJMP *\n.defsection next, $0310\n.section next\n.word here, *"));
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
        String src = org("$0205", ".byte 5\n.defsection low, $0200\n.section low\n.byte 1");
        assertBytes(src, 0x01, 0, 0, 0, 0, 0x05);
        assertEquals(0x0200, ok(src).origin());
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
        assertTrue(errors(org("BEQ far\n.defsection high, $0300\n.section high\nfar: RTS")).contains("branch target out of range"));
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
    void emittingBeforeTheFirstSectionIsAnError() {
        assertTrue(errors("LDA #1\n").contains("no Section yet"));
        assertTrue(errors(".byte 1\n").contains("no Section yet"));
    }

    @Test
    void errorHasLocationSourceLineAndCaret() {
        assertEquals("t.s:3:5: error: value $1FF does not fit in a byte\nLDA #$1FF\n    ^", errors(org("LDA #$1FF")));
        assertEquals("t.s:3:2: error: unknown instruction 'FOO'\n\tFOO\n\t^", errors(org("\tFOO")));
    }

    @Test
    void everyErrorOfTheRunIsReported() {
        var r = (Errors) assemble(org("FOO\nLDA #$100\nJMP nowhere\nNOP"));
        assertEquals(3, r.messages().size());
        assertTrue(r.messages().get(0).startsWith("t.s:3:1: error: unknown instruction"));
        assertTrue(r.messages().get(1).startsWith("t.s:4:5: error: value $100"));
        assertTrue(r.messages().get(2).startsWith("t.s:5:5: error: undefined symbol nowhere"));
    }

    @Test
    void listingShowsAddressBytesAndSourceWithLongDataWrapped() {
        var out = ok("""
                FOO = 1
                .defsection code, $0200
                .section code
                ; hi
                start:  LDA #$2A
                        STA $D300
                        .word start, start
                """);
        assertEquals("""
                                FOO = 1
                                .defsection code, $0200
                0200            .section code
                0200            ; hi
                0200  A9 2A     start:  LDA #$2A
                0202  8D 00 D3          STA $D300
                0205  00 02 00          .word start, start
                0208  02
                """, out.listing());
    }

    @Test
    void listingShowsResolvedForwardReferences() {
        assertEquals("                .defsection code, $0200\n0200            .section code\n0200  4C 03 02  JMP l\n0203  60        l: RTS\n",
                ok(org("JMP l\nl: RTS")).listing());
    }

    @Test
    void symbolFileSortedByValue() {
        var out = ok("""
                .defsection code, $0200
                .section code
                COUT = $D000
                ZERO = 0
                start: NOP
                end: RTS
                """);
        assertEquals("0000 ZERO const local\n0200 start label local\n0201 end label local\nD000 COUT const local\n",
                symbolLines(out));
    }

    @Test
    void symbolFileListsDataRangesMergingAdjacentOnes() {
        var out = ok("""
                .defsection code, $0200
                .section code
                NOP
                .byte 1, 2
                .word 3
                NOP
                .res 3
                .align 8
                .defsection high, $0300
                .section high
                .byte "a"
                """);
        assertEquals("data 0201 0004\ndata 0206 000A\ndata 0300 0001\n", symbolLines(out));
    }

    @Test
    void operatorsByPrecedence() {
        assertBytes(org(".byte 2+3*4, (2+3)*4, 10-4-3, 17/2%5, 1+2<<1, 6&1<<1, 6^3&1, 2|1^3"),
                14, 20, 3, 3, 6, 2, 7, 2);
        assertBytes(org(".byte -1, - -2, ~0&$FF, 3*-2+10\n.word $1234>>4, -2"), 0xFF, 2, 0xFF, 4, 0x23, 0x01, 0xFE, 0xFF);
    }

    @Test
    void percentIsModuloAfterAValueAndBinaryElsewhere() {
        assertBytes(org(".byte *%3, %101, 10 % 3, 10%4, (7)%4\nl: LDA %11\nLDA #%1"), 2, 5, 1, 2, 3, 0xA5, 3, 0xA9, 1);
    }

    @Test
    void divisionByZeroIsAnError() {
        assertTrue(errors(org(".byte 1/0")).contains("division by zero"));
        assertTrue(errors(org(".byte 1%0")).contains("division by zero"));
    }

    @Test
    void onlySymPlusMinusConstIsAllowedOnUnresolvedValues() {
        assertBytes("K = 1\n" + org("LDA #fwd+K*2\nLDA #K+fwd\nLDA #fwd-1\nfwd = 5"), 0xA9, 7, 0xA9, 6, 0xA9, 4);
        assertTrue(errors(org(".byte fwd*2\nfwd = 1")).contains("define fwd before this use"));
        assertTrue(errors(org(".byte -fwd\nfwd = 1")).contains("define fwd before this use"));
        assertTrue(errors(org(".byte 1-fwd\nfwd = 1")).contains("define fwd before this use"));
    }

    @Test
    void differenceOfLabelsNeedsBothDefinedFirst() {
        assertBytes(org("start: .byte 1, 2\nend: .byte end - start"), 1, 2, 2);
        assertEquals("t.s:4:18: error: define end before this use\n       .byte end - start\n                 ^",
                errors(org("start: .byte 1, 2\n       .byte end - start\nend:")));
    }

    @Test
    void lowAndHighByteApplyToTheWholeExpression() {
        assertBytes(org(".byte <$1234, >$1234, <$12FF+1, >$12FF+1, >$1234 & $FF00"), 0x34, 0x12, 0x00, 0x13, 0x12);
        assertBytes(org("LDA #<fwd\nLDX #>fwd\n.byte <fwd+1, >fwd+$100\nfwd = $1234"), 0xA9, 0x34, 0xA2, 0x12, 0x35, 0x13);
        assertTrue(errors(org(".byte 1+<2")).contains("'<' and '>' may only start an expression"));
        assertBytes(org("LDA z:<fwd\nLDA a:<fwd\n.word >fwd\nfwd = $1234"), 0xA5, 0x34, 0xAD, 0x34, 0x00, 0x12, 0x00);
    }

    @Test
    void parenthesisedOperandIsIndirectAndTheErrorSuggestsZeroPlus() {
        assertTrue(errors(org("LDA (1+2)")).contains("LDA has no (indirect) mode; to group an expression write 0+(...)"));
        assertBytes(org("LDA 0+(1+2)\nLDA (1+2)*2\nLDA (1+2),Y"), 0xA5, 3, 0xA5, 6, 0xB1, 3);
    }

    @Test
    void stringsInByteWithEscapes() {
        assertBytes(org(".byte \"Hi\", 0, \";\" ; comment"), 'H', 'i', 0, ';');
        assertBytes(org(".byte \"\\n\\r\\t\\0\\\\\\\"\\'\\x41\\xff\""), 10, 13, 9, 0, 0x5C, 0x22, 0x27, 0x41, 0xFF);
    }

    @Test
    void nonAsciiStringCharactersEmitTheirUtf8Bytes() {
        assertBytes(org(".byte \"\u00E9\u20AC\uD83D\uDE00\""), 0xC3, 0xA9, 0xE2, 0x82, 0xAC, 0xF0, 0x9F, 0x98, 0x80);
    }

    @Test
    void badStringsAreErrors() {
        assertTrue(errors(org(".byte \"abc")).contains("unterminated string"));
        assertTrue(errors(org(".byte \"a\\q\"")).contains("t.s:3:9: error: unknown escape '\\q'"));
        assertTrue(errors(org(".byte \"\\x4\"")).contains("\\x needs two hex digits"));
    }

    @Test
    void cheapLocalLabelsAreReusableAcrossScopes() {
        var src = org("""
                a:     LDX #2
                @loop: DEX
                       BNE @loop
                b:     LDY #2
                @loop: DEY
                       BNE @loop
                       BEQ @done
                @done: RTS""");
        assertBytes(src, 0xA2, 2, 0xCA, 0xD0, 0xFD, 0xA0, 2, 0x88, 0xD0, 0xFD, 0xF0, 0x00, 0x60);
        assertEquals("""
                0200 a label local
                0202 a@loop label local
                0205 b label local
                0207 b@loop label local
                020C b@done label local
                """, symbolLines(ok(src)));
    }

    @Test
    void constantDefinitionDoesNotOpenACheapLocalScope() {
        assertBytes(org("a:\n@x: NOP\nK = 1\nJMP @x"), 0xEA, 0x4C, 0x00, 0x02);
    }

    @Test
    void cheapLocalLabelOutsideItsScopeIsAnError() {
        assertTrue(errors(org("a:\n@x: NOP\nb: JMP @x")).contains("t.s:5:8: error: undefined symbol @x in the scope of b"));
        assertTrue(errors(org("@x: NOP")).contains("cheap local label @x needs an ordinary label before it"));
        assertTrue(errors(org("a: @x = 1")).contains("@x: a cheap local label must be a label, not a constant"));
    }

    @Test
    void resReservesBytesWithAnOptionalFill() {
        assertBytes(org(".res 3\n.res 2, $EA\n.res 0\n.byte 1"), 0, 0, 0, 0xEA, 0xEA, 1);
    }

    @Test
    void alignPadsToAMultipleWithAnOptionalFill() {
        assertBytes(org(".align 2\n.byte 1\n.align 4\n.byte 2\n.align 4, $FF\n.byte 3"), 1, 0, 0, 0, 2, 0xFF, 0xFF, 0xFF, 3);
    }

    @Test
    void resAndAlignOperandsMustBeResolvedAndInRange() {
        assertTrue(errors(org(".res n\nn = 2")).contains("define n before this use"));
        assertTrue(errors(org(".align n\nn = 2")).contains("define n before this use"));
        assertTrue(errors(org(".res 1, f\nf = 2")).contains("define f before this use"));
        assertTrue(errors(org(".res -1")).contains(".res count -1 is negative"));
        assertTrue(errors(org(".align 0")).contains(".align boundary 0 is not positive"));
        assertTrue(errors(org(".res 2, $100")).contains("value $100 does not fit in a byte"));
        assertTrue(errors(org(".res $FFFFFF")).contains("code runs past $FFFF"));
    }

    @Test
    void listingShowsReservedSpaceAsAByteCount() {
        assertEquals("                .defsection code, $0200\n0200            .section code\n0200  (5 bytes)  .res 5\n0205  (3 bytes)  .align 8\n",
                ok(org(".res 5\n.align 8")).listing());
    }

    /** Assembles {@code src/main.s} from in-memory files keyed by path. */
    private static Assembler.Result assemble(Map<String, String> files) {
        try {
            return Assembler.assemble(Path.of("src/main.s"), p -> {
                String text = files.get(p.toString());
                if (text == null) throw new NoSuchFileException(p.toString());
                return text.getBytes(UTF_8);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Output ok(Map<String, String> files) {
        return ok(assemble(files));
    }

    private static final Map<String, String> PROGRAM = Map.of(
            "src/main.s", ".defsection code, $0200\n.section code\n.include \"lib/util.s\"\nJMP util\n.incbin \"data.bin\"\n",
            "src/lib/util.s", "K = 7\n.include \"consts.s\"\nutil: LDA #K+C\n",
            "src/lib/consts.s", "C = 1\n",
            "src/data.bin", "\u0001\u0002\u0003\u0004");

    @Test
    void includeAndIncbinAreRelativeToTheIncludingFile() {
        var out = (Output) assemble(PROGRAM);
        var expected = new byte[] {(byte) 0xA9, 8, 0x4C, 0x00, 0x02, 1, 2, 3, 4};
        assertArrayEquals(expected, out.binary());
    }

    @Test
    void listingMarksEachFileChangeAndShowsIncbinAsAByteCount() {
        assertEquals("""
                                .defsection code, $0200
                0200            .section code
                0200            .include "lib/util.s"
                ; file: src/lib/util.s
                0200            K = 7
                0200            .include "consts.s"
                ; file: src/lib/consts.s
                0200            C = 1
                ; file: src/lib/util.s
                0200  A9 08     util: LDA #K+C
                ; file: src/main.s
                0202  4C 00 02  JMP util
                0205  (4 bytes)  .incbin "data.bin"
                """, ((Output) assemble(PROGRAM)).listing());
    }

    @Test
    void errorsInAnIncludedFileNameThatFile() {
        var r = (Errors) assemble(Map.of("src/main.s", org("0", ".include \"x.s\""), "src/x.s", "\nFOO\n"));
        assertEquals(1, r.messages().size());
        assertTrue(r.messages().getFirst().startsWith("src/x.s:2:1: error: unknown instruction 'FOO'"));
    }

    @Test
    void recursiveIncludeIsAnError() {
        var r = (Errors) assemble(Map.of("src/main.s", ".include \"a.s\"\n", "src/a.s", ".include \"./main.s\"\n"));
        assertEquals(1, r.messages().size());
        assertTrue(r.messages().getFirst().startsWith("src/a.s:1:10: error: recursive .include of src/main.s"));
        assertTrue(errors(".include \"t.s\"").contains("recursive .include of t.s"));
    }

    @Test
    void anIncludeLeavesTheCallerInItsSection() {
        String header = ".defsection code, $0200\n.defsection data, $0204\n.section code\n";
        var out = ok(Map.of(
                "src/main.s", header + "NOP\n.include \"lib.s\"\nLDA #0\n",
                "src/lib.s", ".section data\n.byte 7\n"));
        assertArrayEquals(new byte[] {(byte) 0xEA, (byte) 0xA9, 0, 0, 7}, out.binary());
        assertTrue(out.listing().endsWith("; file: src/main.s\n0201  A9 00     LDA #0\n"), out.listing());

        var deeper = ok(Map.of(
                "src/main.s", header + "NOP\n.include \"lib.s\"\nLDA #0\n",
                "src/lib.s", ".include \"inner.s\"\nmid:\n",
                "src/inner.s", ".section data\n.byte 7\n"));
        assertArrayEquals(out.binary(), deeper.binary());
        assertTrue(deeper.symbols().contains("0201 mid label local"), deeper.symbols());
    }

    @Test
    void anIncludeBeforeTheFirstSectionLeavesTheCallerInNone() {
        var r = assertInstanceOf(Errors.class, assemble(Map.of(
                "src/main.s", ".include \"lib.s\"\nNOP\n",
                "src/lib.s", ".defsection code, $0200\n.section code\nRTS\n")));
        assertTrue(r.messages().getFirst().startsWith("src/main.s:2:1: error: no Section yet"));
    }

    @Test
    void anIncludeThatDeclaresItsOwnSectionLeavesTheCallerInItsSection() {
        var out = ok(Map.of(
                "src/main.s", org("NOP\n.include \"lib.s\"\nLDA #0"),
                "src/lib.s", ".defsection lib, $0203\n.section lib\n.byte 7\n"));
        assertArrayEquals(new byte[] {(byte) 0xEA, (byte) 0xA9, 0, 7}, out.binary());
    }

    @Test
    void aSwitchOfSectionKeepsTheScopeOfEachSection() {
        var out = ok("""
                        .defsection code, $0200
                        .defsection data, $0280
                        .section code
                print:  ldx #0
                        .section data
                tbl:    .byte 1
                @end:
                        .section code
                @loop:  inx
                        bne @loop
                        rts
                """);
        assertEquals("""
                0200 print label local
                0202 print@loop label local
                0280 tbl label local
                0281 tbl@end label local
                data 0280 0001
                """, symbolLines(out));
    }

    @Test
    void anIncludeInsideARoutineKeepsItsScope() {
        var out = ok(Map.of("src/main.s", """
                        .defsection code, $0200
                        .section code
                start:  ldx #1
                @loop:  dex
                        .include "inc.s"
                        bne @loop
                        rts
                """, "src/inc.s", "helper: rts\n"));
        assertEquals("A2 01 CA 60 D0 FC 60", HexFormat.ofDelimiter(" ").withUpperCase().formatHex(out.binary()));
    }

    @Test
    void anIncludedFileHasNoScopeUntilItsOwnLabel() {
        String main = """
                .defsection code, $0200
                .defsection data, $0280
                .section data
                tbl: .byte 0
                .section code
                start:
                @x: NOP
                .include "inc.s"
                """;
        for (var line : new String[] {"JMP @x", "@y: RTS", ".section data\n.word @x"}) {
            var r = assertInstanceOf(Errors.class, assemble(Map.of("src/main.s", main, "src/inc.s", line + "\n")), line);
            assertTrue(r.messages().getFirst().contains("needs an ordinary label before it in this Section and file"), line);
        }
    }

    @Test
    void aNewSectionStartsWithNoScope() {
        assertTrue(errors(org("a: NOP\n.defsection high, $0300\n.section high\n@x: RTS")).contains("needs an ordinary label before it"));
    }

    @Test
    void forwardZeroPageCheckHoldsInAContinuedSectionAndAfterAnInclude() {
        var r = assertInstanceOf(Errors.class, assemble(Map.of("src/main.s", """
                .defsection code, $0200
                .defsection data, $0300
                .defsection vars, $10
                .section code
                jmp later
                .section data
                .word ptr
                .section code
                sta ptr
                .include "lib.s"
                stx ptr+1
                later: rts
                .section vars
                ptr:
                """, "src/lib.s", "ldy ptr\n.section data\n.word later\n")));
        assertEquals(3, r.messages().size());
        assertTrue(r.messages().get(0).startsWith("src/main.s:9:5: error: operand $10 is in zero page: define ptr"));
        assertTrue(r.messages().get(1).startsWith("src/lib.s:1:5: error: operand $10 is in zero page: define ptr"));
        assertTrue(r.messages().get(2).startsWith("src/main.s:11:5: error: operand $11 is in zero page: define ptr"));
    }

    @Test
    void declarationsMadeByAnIncludedFileStay() {
        var out = ok(Map.of(
                "src/main.s", ".include \"map.inc\"\n.section code\nNOP\n",
                "src/map.inc", ".defsection code, $0200\n"));
        assertEquals(0x0200, out.origin());
        var r = assertInstanceOf(Errors.class, assemble(Map.of(
                "src/main.s", ".include \"map.inc\"\n.defsection code, $0200\n",
                "src/map.inc", ".defsection code, $0200\n")));
        assertTrue(r.messages().getFirst().startsWith("src/main.s:2:13: error: Section code is already declared"));
    }

    @Test
    void unreadableOrMissingFileNamesAreErrors() {
        var r = (Errors) assemble(Map.of("src/main.s", org("0", ".include \"nope.s\"\n.incbin \"nope.bin\"")));
        assertTrue(r.messages().get(0).startsWith("src/main.s:3:10: error: cannot read src/nope.s"));
        assertTrue(r.messages().get(1).startsWith("src/main.s:4:9: error: cannot read src/nope.bin"));
        assertTrue(errors(org(".include nope")).contains(".include needs a \"file\" name"));
        assertTrue(errors(".incbin \"t.s\"").contains("no Section yet"));
    }

    private static final String CODE_AND_DATA = ".defsection code, $0200\n.defsection data, $0208\n";

    @Test
    void sectionContinuesANamedSectionWhereItLeftOff() {
        assertBytes(CODE_AND_DATA + """
                .section code
                LDA first
                .section data
                first: .byte 1
                .section code
                LDA second
                .section data
                second: .byte 2
                """, 0xAD, 0x08, 0x02, 0xAD, 0x09, 0x02, 0, 0, 1, 2);
    }

    @Test
    void listingAndDataLinesFollowAContinuedSection() {
        var out = ok("""
                .defsection code, $0200
                .defsection data, $0208
                K = 1
                .section code
                NOP
                .section data
                .byte 1, 2
                .defsection rest, *
                .section code
                .byte 3
                .section data
                ; more
                .res 2
                .section code
                RTS
                """);
        assertEquals("""
                                .defsection code, $0200
                                .defsection data, $0208
                                K = 1
                0200            .section code
                0200  EA        NOP
                0208            .section data
                0208  01 02     .byte 1, 2
                020A            .defsection rest, *
                0201            .section code
                0201  03        .byte 3
                020A            .section data
                020A            ; more
                020A  (2 bytes)  .res 2
                0202            .section code
                0202  60        RTS
                """, out.listing());
        assertEquals("0001 K const local\ndata 0201 0001\ndata 0208 0004\n", symbolLines(out));
    }

    @Test
    void declaringASectionDoesNotSwitchToIt() {
        String header = ".defsection code, $0200\n.defsection data, $0280\n";
        for (var line : new String[] {"NOP", ".byte 1", "l:", "K = *", ".res 1"})
            assertTrue(errors(header + line).contains("no Section yet: switch to one with .section first"), line);
        assertBytes("K = 7\n" + header + "L = K+1\n.section code\n.byte K, L", 7, 8);
    }

    @Test
    void aSectionNameIsDeclaredOnceBeforeUse() {
        assertEquals("t.s:2:13: error: Section code is already declared\n.defsection code, $0200\n            ^",
                errors(".defsection code, $0200\n.defsection code, $0200"));
        assertTrue(errors(".section cdoe").startsWith(
                "t.s:1:10: error: Section cdoe is not declared: declare it with .defsection first"));
        assertTrue(errors(".defsection code, 0\n.section Code").contains("Section Code is not declared"));
    }

    @Test
    void aSectionNameFollowsTheSymbolRules() {
        for (var name : new String[] {"", "@x", "12", "\"code\"", ".code"}) {
            assertTrue(errors(".defsection " + name + ", 0").contains("expected a Section name"), name);
            assertTrue(errors(".section " + name).contains("expected a Section name"), name);
        }
    }

    @Test
    void anOriginIsKnownOnItsLineAndInsideTheAddressSpace() {
        assertTrue(errors(".defsection s, $10000").startsWith("t.s:1:16: error: origin $10000 is outside $0000-$FFFF"));
        assertTrue(errors(".defsection s, -1").contains("origin -1 is outside $0000-$FFFF"));
        assertTrue(errors(".defsection s, later\nlater = 1").contains("define later before this use"));
        assertBytes(".defsection s, $FFFF\n.section s\n.byte 1", 1);
        assertBytes("BASE = $0200\n.defsection code, BASE\n.section code\nNOP\nend:\n.defsection data, end+1\n.section data\n.byte 1", 0xEA, 0, 1);
    }

    @Test
    void aLabelOnASectionDirectiveLineIsAnError() {
        assertEquals("t.s:2:1: error: a label can't be on a .section line: put it on the next line\nl: .section s\n^",
                errors(".defsection s, 0\nl: .section s"));
        assertEquals("t.s:1:1: error: a label can't be on a .defsection line: put it on the next line\nl: .DefSection s, 0\n^",
                errors("l: .DefSection s, 0"));
        assertTrue(errors(org("a:\n@l: .defsection s, 0")).contains("a label can't be on a .defsection line"));
    }

    @Test
    void sectionNamesAreApartFromSymbolsAndDirectivesIgnoreCase() {
        assertBytes(".DEFSECTION code, $0200\n.Section code\ncode: JMP code", 0x4C, 0x00, 0x02);
    }

    @Test
    void starEqualsIsAnErrorThatNamesBothDirectives() {
        String error = "error: *= is not supported: declare a Section with .defsection and switch to it with .section";
        assertEquals("t.s:1:1: " + error + "\n*= $0200\n^", errors("*= $0200"));
        assertTrue(errors("\t* = later").startsWith("t.s:1:2: " + error));
        assertTrue(errors("p: *= *+2").startsWith("t.s:1:4: " + error));
        assertTrue(errors(org("*= $0300")).startsWith("t.s:3:1: " + error));
        assertTrue(errors(org("p: *= *+2")).startsWith("t.s:3:4: " + error));
        assertTrue(errors(".defsection zp, $10, reserved\n.section zp\np: *= *+2").startsWith("t.s:3:4: " + error));
        var r = assertInstanceOf(Errors.class, assemble(Map.of("src/main.s", org(".include \"lib.s\""), "src/lib.s", "*= $0203\n")));
        assertTrue(r.messages().getFirst().startsWith("src/lib.s:1:1: " + error));
    }

    @Test
    void anOverlapNamesTheSectionsAtTheHigherDeclaration() {
        assertEquals("t.s:2:1: error: Section data ($0208-$0209) overlaps Section code ($0200-$0209)\n.defsection data, $0208\n^",
                errors(CODE_AND_DATA + ".section code\n.res 4\n.section data\n.word 0\n.section code\n.res 6\n"));
        assertTrue(errors(".defsection code, $0200\n.defsection low, $01FF\n.section code\nNOP\n.section low\n.word 0")
                .startsWith("t.s:1:1: error: Section code ($0200-$0200) overlaps Section low ($01FF-$0200)"));
        assertTrue(errors(org(".word 0\n.defsection data, $0201\n.section data\n.byte 1"))
                .startsWith("t.s:4:1: error: Section data ($0201-$0201) overlaps Section code ($0200-$0201)"));
    }

    @Test
    void everyOverlapIsReportedAlsoAtEqualOriginsAndInsideALargerSection() {
        assertEquals("t.s:2:1: error: Section b ($0200-$0200) overlaps Section a ($0200-$0200)\n.defsection b, $0200\n^",
                errors(".defsection a, $0200\n.defsection b, $0200\n.section a\n.byte 1\n.section b\n.byte 2"));
        assertEquals("""
                t.s:2:1: error: Section b ($0210-$0210) overlaps Section a ($0200-$02FF)
                .defsection b, $0210
                ^
                t.s:3:1: error: Section c ($0220-$0220) overlaps Section a ($0200-$02FF)
                .defsection c, $0220
                ^""", errors("""
                .defsection a, $0200
                .defsection b, $0210
                .defsection c, $0220
                .section a
                .res 256
                .section b
                .res 1
                .section c
                .res 1
                """));
    }

    @Test
    void resolveErrorsAreInSourceOrderAcrossSectionsAndIncludes() {
        var r = assertInstanceOf(Errors.class, assemble(Map.of("src/main.s", """
                .defsection data, $0300
                .defsection code, $0200
                .section code
                lda x
                .section data
                .word none
                .include "lib.s"
                .section data
                .word last
                x = 1
                """, "src/lib.s", ".word first\n.section code\nldy missing\n")));
        assertArrayEquals(new String[] {"src/main.s:4:5", "src/main.s:6:7", "src/lib.s:1:7", "src/lib.s:3:5", "src/main.s:9:7"},
                r.messages().stream().map(m -> m.substring(0, m.indexOf(": error"))).toArray(String[]::new));
    }

    @Test
    void emptySectionsNeitherOverlapNorWidenTheImage() {
        String unused = ".defsection unused, $10\n.defsection code, $0200\n.defsection same, $0200\n.defsection inside, $0201\n";
        assertBytes(unused + ".section code\nLDA #1", 0xA9, 1);
        assertEquals(0x0200, ok(unused + ".section code\nLDA #1\n.section inside\n").origin());
    }

    @Test
    void anImageWithNoBytesHasTheOriginOfTheFirstDeclaredLoadedSection() {
        var out = ok(".defsection high, $0400\n.defsection low, $0200\n.section low\n");
        assertEquals(0, out.binary().length);
        assertEquals(0x0400, out.origin());
        assertEquals(0x0400, ok(".defsection zp, $10, reserved\n.defsection code, $0400\n.section zp\n.res 2").origin());
        assertEquals(0, ok("K = 1\n").origin());
    }

    @Test
    void variablesInAReservedSectionGetTheirOwnAddresses() {
        String vars = """
                .defsection zp, $10, reserved
                .defsection code, $0200
                .section zp
                p: .res 2
                .section code
                NOP
                .section zp
                q: .res 2
                """;
        assertTrue(symbolLines(ok(vars)).startsWith("0010 p label local\n0012 q label local\n"));
    }

    @Test
    void reservedIgnoresCaseAndIsTheOnlyWordAfterTheOrigin() {
        for (var word : new String[] {"RESERVED", "Reserved"})
            assertEquals(0x0200, ok(".defsection zp, $10, " + word + "\n.defsection code, $0200\n.section code\nNOP").origin());
        assertTrue(errors(".defsection zp, $10, resrved").startsWith("t.s:1:22: error: expected 'reserved'"));
        assertTrue(errors(".defsection zp, $10,").startsWith("t.s:1:21: error: expected 'reserved'"));
        assertTrue(errors(".defsection zp, $10, reserved, 2").contains("unexpected ','"));
    }

    @Test
    void reservedMemoryNeverWidensTheImage() {
        var out = ok("""
                .defsection zp, $10, reserved
                .defsection code, $0200
                .defsection buf, $0400, reserved
                .section zp
                ptr: .res 2
                .section buf
                .res 256
                .section code
                STA ptr
                """);
        assertEquals(0x0200, out.origin());
        assertArrayEquals(new byte[] {(byte) 0x85, 0x10}, out.binary());
    }

    @Test
    void aRomImageHasItsVariablesOutside() {
        var out = ok("""
                .defsection vars, $10, reserved
                .defsection code, $E000
                .defsection vectors, $FFFA
                .section vars
                ticks: .res 2
                .section code
                start: INC ticks
                irq: RTI
                .section vectors
                .word irq, start, irq
                """);
        assertEquals(0xE000, out.origin());
        var bin = out.binary();
        assertEquals(8192, bin.length);
        assertArrayEquals(new byte[] {(byte) 0xE6, 0x10, 0x40}, Arrays.copyOf(bin, 3));
        assertArrayEquals(new byte[] {2, (byte) 0xE0, 0, (byte) 0xE0, 2, (byte) 0xE0}, Arrays.copyOfRange(bin, 8186, 8192));
    }

    @Test
    void onlyReservedSectionsGiveAnEmptyImageAtOriginZero() {
        var out = ok(".defsection zp, $10, reserved\n.section zp\np: .res 2");
        assertEquals(0, out.binary().length);
        assertEquals(0, out.origin());
    }

    @Test
    void reservedLinesListTheirSizeAndWriteNoDataLine() {
        var out = ok("""
                .defsection vars, $0300, reserved
                .section vars
                p: .res 2
                buf:
                @end: .res 4
                .align 8
                """);
        assertEquals("""
                                .defsection vars, $0300, reserved
                0300            .section vars
                0300  (2 bytes)  p: .res 2
                0302            buf:
                0302  (4 bytes)  @end: .res 4
                0306  (2 bytes)  .align 8
                """, out.listing());
        assertEquals("0300 p label local\n0302 buf label local\n0302 buf@end label local\n", out.symbols());
    }

    @Test
    void aReservedSectionCantHoldCodeValuesOrFills() {
        String vars = ".defsection vars, $0300, reserved\n.section vars\n";
        String notLoaded = " can't be in Reserved Section vars: it puts no bytes in the image";
        assertEquals("t.s:3:4: error: instructions" + notLoaded + "\nv: LDA #1\n   ^", errors(vars + "v: LDA #1"));
        assertTrue(errors(vars + "nop").contains("t.s:3:1: error: instructions" + notLoaded));
        assertTrue(errors(vars + ".BYTE 1").contains("t.s:3:1: error: .byte" + notLoaded));
        assertTrue(errors(vars + ".byte \"hi\"").contains("t.s:3:1: error: .byte" + notLoaded));
        assertTrue(errors(vars + ".word 0").contains("t.s:3:1: error: .word" + notLoaded));
        assertTrue(errors(vars + ".incbin \"t.s\"").contains("t.s:3:1: error: .incbin" + notLoaded));
        assertTrue(errors(vars + ".res 2, 0").contains("t.s:3:9: error: a fill" + notLoaded));
        assertTrue(errors(vars + ".align 4, $EA").contains("t.s:3:11: error: a fill" + notLoaded));
    }

    @Test
    void aReservedSectionHoldsNamesSpaceAndWhatEmitsNothing() {
        var out = ok(Map.of("src/main.s", """
                .defsection vars, $0301, reserved
                .section vars
                SIZE = 3
                here = *
                a:
                @next: .res SIZE
                .align 8
                b: .include "lib.s"
                .defsection more, $0400, reserved
                c: .res 1
                .section more
                d: .res 2
                """, "src/lib.s", "e: .res 2\n"));
        assertEquals(0, out.binary().length);
        assertEquals("""
                0003 SIZE const local
                0301 here const local
                0301 a label local
                0301 a@next label local
                0308 b label local
                0308 e label local
                030A c label local
                0400 d label local
                """, out.symbols());
    }

    @Test
    void overlapWithReservedMemoryIsReportedLikeAnyOverlap() {
        assertEquals("t.s:2:1: error: Section code ($0300-$0302) overlaps Section vars ($02F8-$0300)\n.defsection code, $0300\n^",
                errors(".defsection vars, $02F8, reserved\n.defsection code, $0300\n.section vars\n.res 9\n.section code\nJMP *"));
        assertTrue(errors(".defsection code, $0200\n.defsection vars, $0202, reserved\n.section code\nJMP *\n.section vars\n.res 1")
                .startsWith("t.s:2:1: error: Section vars ($0202-$0202) overlaps Section code ($0200-$0202)"));
        assertTrue(errors(".defsection a, $10, reserved\n.defsection b, $12, reserved\n.section a\n.res 3\n.section b\n.res 1")
                .startsWith("t.s:2:1: error: Section b ($0012-$0012) overlaps Section a ($0010-$0012)"));
        assertBytes(".defsection empty, $0201, reserved\n.defsection code, $0200\n.section code\nJMP *", 0x4C, 0x00, 0x02);
    }

    @Test
    void reservedZeroPageEndsByFF() {
        String vars = ".defsection vars, $F0, reserved\n.section vars\n";
        assertTrue(ok(vars + ".res 16\nend:").symbols().contains("0100 end label local"));
        assertEquals("t.s:3:6: error: Reserved Section vars runs past $FF\n.res 17\n     ^", errors(vars + ".res 17"));
        assertTrue(errors(vars + ".res 8\n.res 8\n.res 1").startsWith("t.s:5:6: error: Reserved Section vars runs past $FF"));
        assertTrue(errors(vars + ".res 1\n.align 512").startsWith("t.s:4:8: error: Reserved Section vars runs past $FF"));
        assertTrue(ok(".defsection vars, $F1, reserved\n.section vars\n.res 1\n.align 32\nend:").symbols().contains("0100 end label local"));
        assertEquals(32, ok(".defsection low, $F0\n.section low\n.res 32").binary().length);
        assertTrue(ok(".defsection page1, $100, reserved\n.section page1\n.res 256\nend:").symbols().contains("0200 end label local"));
        assertTrue(errors(".defsection high, $FFF0, reserved\n.section high\n.res 17")
                .startsWith("t.s:3:6: error: Reserved Section high runs past $FFFF"));
    }

    @Test
    void overlappingSectionsAreAnError() {
        assertEquals("t.s:4:1: error: Section high ($0202-$0202) overlaps Section code ($0200-$0202)\n.defsection high, $0202\n^",
                errors(org(".byte 1, 2, 3\n.defsection high, $0202\n.section high\n.byte 4")));
        assertTrue(errors(org("$0300", ".byte 1\n.defsection low, $0200\n.section low\n.res $101")).contains("overlaps"));
        assertBytes(org(".defsection same, $0200\n.section same\n.byte 1\n.defsection next, $0201\n.section next\n.byte 2"), 1, 2);
    }

    @Test
    void variablesAndEmptySectionsOutsideTheImageAddNothing() {
        String src = """
                .defsection vars, $80, reserved
                .section vars
                ptr: .res 2
                .defsection below, *
                .section below
                .defsection code, $0200
                .section code
                LDA ptr
                .defsection above, $0300
                .section above
                """;
        assertEquals(0x0200, ok(src).origin());
        assertBytes(src, 0xA5, 0x80);
    }
}
