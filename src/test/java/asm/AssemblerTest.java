package asm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler.Errors;
import asm.Assembler.Output;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
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

    @Test
    void symbolFileListsDataRangesMergingAdjacentOnes() {
        var out = ok("""
                *= $0200
                NOP
                .byte 1, 2
                .word 3
                NOP
                .res 3
                .align 8
                *= $0300
                .byte "a"
                """);
        assertEquals("data 0201 0004\ndata 0206 000A\ndata 0300 0001\n", out.symbols());
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
        assertEquals("t.s:3:18: error: define end before this use\n       .byte end - start\n                 ^",
                errors(org("start: .byte 1, 2\n       .byte end - start\nend:")));
    }

    @Test
    void lowAndHighByteApplyToTheWholeExpression() {
        assertBytes(org(".byte <$1234, >$1234, <$12FF+1, >$12FF+1, >$1234 & $FF00"), 0x34, 0x12, 0x00, 0x13, 0x12);
        assertBytes(org("LDA #<fwd\nLDX #>fwd\n.byte <fwd+1, >fwd+$100\nfwd = $1234"), 0xA9, 0x34, 0xA2, 0x12, 0x35, 0x13);
        assertTrue(errors(org(".byte 1+<2")).contains("'<' and '>' may only start an expression"));
        assertBytes(org("LDA <fwd\n.word >fwd\nfwd = $1234"), 0xAD, 0x34, 0x00, 0x12, 0x00); // forward: absolute
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
        assertTrue(errors(org(".byte \"a\\q\"")).contains("t.s:2:9: error: unknown escape '\\q'"));
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
                """, ok(src).symbols());
    }

    @Test
    void constantDefinitionDoesNotOpenACheapLocalScope() {
        assertBytes(org("a:\n@x: NOP\nK = 1\nJMP @x"), 0xEA, 0x4C, 0x00, 0x02);
    }

    @Test
    void cheapLocalLabelOutsideItsScopeIsAnError() {
        assertTrue(errors(org("a:\n@x: NOP\nb: JMP @x")).contains("t.s:4:8: error: undefined symbol @x in the scope of b"));
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
        assertEquals("0200            *= $0200\n0200  (5 bytes)  .res 5\n0205  (3 bytes)  .align 8\n",
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

    private static final Map<String, String> PROGRAM = Map.of(
            "src/main.s", "*= $0200\n.include \"lib/util.s\"\nJMP util\n.incbin \"data.bin\"\n",
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
                0200            *= $0200
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
        var r = (Errors) assemble(Map.of("src/main.s", "*= 0\n.include \"x.s\"\n", "src/x.s", "\nFOO\n"));
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
    void unreadableOrMissingFileNamesAreErrors() {
        var r = (Errors) assemble(Map.of("src/main.s", "*= 0\n.include \"nope.s\"\n.incbin \"nope.bin\"\n"));
        assertTrue(r.messages().get(0).startsWith("src/main.s:2:10: error: cannot read src/nope.s"));
        assertTrue(r.messages().get(1).startsWith("src/main.s:3:9: error: cannot read src/nope.bin"));
        assertTrue(errors(org(".include nope")).contains(".include needs a \"file\" name"));
        assertTrue(errors(".incbin \"t.s\"").contains("no origin yet"));
    }

    @Test
    void overlappingSectionsAreAnError() {
        assertEquals("t.s:3:1: error: Section $0202-$0202 overlaps Section $0200-$0202\n*= $0202\n^",
                errors("*= $0200\n.byte 1, 2, 3\n*= $0202\n.byte 4\n"));
        assertTrue(errors("*= $0300\n.byte 1\n*= $0200\n.res $101\n").contains("overlaps"));
        assertBytes("*= $0200\n*= $0200\n.byte 1\n*= $0201\n.byte 2\n", 1, 2);
    }

    @Test
    void emptySectionsOutsideTheImageAddNothing() {
        var out = ok("*= $80\nptr: *= *+2\n*= $0200\nLDA ptr\n*= $0300\n");
        assertEquals(0x0200, out.origin());
        assertBytes("*= $80\nptr: *= *+2\n*= $0200\nLDA ptr\n*= $0300\n", 0xA5, 0x80);
    }
}
