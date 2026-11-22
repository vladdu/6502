package disasm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import asm.Assembler.Errors;
import asm.Assembler.Output;
import cpu.Mode;
import cpu.Opcode;
import cpu.Opcodes;
import disasm.SymbolFile.Data;
import disasm.SymbolFile.Symbol;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import testdata.TestData;

/** Assemble, disassemble, reassemble: the bytes must not change. */
class RoundTripTest {
    /** Symbol names that could read as something else: the Section, a register, a prefix, a mnemonic, a directive. */
    private static final List<String> NAMES = List.of("code", "section", "a", "A", "x", "X", "y", "Y", "z", "Z", "lda", "NOP",
            "byte", "_", "main", "Main");

    private static Output assemble(String src) {
        return assemble(Map.of("t.s", src));
    }

    /** Assembles {@code t.s} of {@code files}, which also holds what it includes. */
    private static Output assemble(Map<String, String> files) {
        try {
            var r = Assembler.assemble(Path.of("t.s"), path -> files.get(path.toString()).getBytes(UTF_8));
            if (r instanceof Errors(var messages)) fail(String.join("\n", messages) + "\nin:\n" + files.get("t.s"));
            return (Output) r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Disassembles {@code image} and reassembles it; returns the disassembly. */
    private static String roundTrip(byte[] image, int org, List<Symbol> symbols) {
        return roundTrip(image, org, new SymbolFile.Contents(symbols, List.of()));
    }

    private static String roundTrip(byte[] image, int org, SymbolFile.Contents symbols) {
        String text = Disassembler.source(image, org, symbols.symbols(), symbols.data());
        Output again = assemble(text);
        assertEquals(org, again.origin(), text);
        assertArrayEquals(image, again.binary(), text);
        return text;
    }

    @Test
    void dormannFunctionalImage() throws IOException {
        roundTrip(Files.readAllBytes(TestData.dormannFunctional()), 0x0000, List.of());
    }

    /** Assembles {@code src}, round-trips its binary with its own symbol file; returns the disassembly. */
    private static String roundTrip(String src) {
        return roundTrip(assemble(src));
    }

    private static String roundTrip(Output out) {
        try {
            return roundTrip(out.binary(), out.origin(), SymbolFile.parse("t.sym", out.symbols()));
        } catch (SymbolFileException e) {
            return fail(e);
        }
    }

    @Test
    void dataFromDirectivesStaysData() {
        String text = roundTrip("""
                .defsection code, $0200
                .section code
                start: LDA msg
                       RTS
                msg:   .byte "Hello, 6502!", 0
                       .word start
                       .res 20, $EA
                       .align 4
                tail:  RTS
                """);
        assertTrue(text.contains(".byte \"Hello, 6502!\", $00, $00, $02, $EA ; 0204 "), text); // .word and .res merge into msg's range
        assertTrue(text.contains("tail:\n        RTS "), text);
        assertFalse(text.contains("PHA"), text);
        assertFalse(text.contains("NOP"), text);
    }

    @Test
    void absoluteOperandsBelowPageOneKeepTheirModeOnlyWhereAZeroPageFormExists() {
        String text = roundTrip("""
                .defsection code, $0200
                .section code
                LDA a:$0012
                LDA a:$0012,X
                LDX a:$0012,Y
                LDA $0012,Y
                JMP $0012
                JSR $00FF
                LDA $12
                STX $12,Y
                """);
        assertTrue(text.contains("LDA a:$0012 "), text);
        assertTrue(text.contains("LDA a:$0012,X "), text);
        assertTrue(text.contains("LDX a:$0012,Y "), text);
        assertTrue(text.contains("LDA $0012,Y "), text);
        assertTrue(text.contains("JMP $0012 "), text);
        assertTrue(text.contains("JSR $00FF "), text);
    }

    @Test
    void zeroPageOperandsNamingLaterLabelsKeepTheirMode() {
        String text = roundTrip("""
                .defsection code, $0010
                .section code
                start:  LDA z:data
                        LDA z:data,X
                        LDX z:data,Y
                        STX z:data,Y
                        LDA (data,X)
                        LDA (data),Y
                        LDA start
                        BNE data
                data:   .byte 1
                """);
        assertTrue(text.contains("LDA z:data "), text);
        assertTrue(text.contains("LDA start "), text);
    }

    @Test
    void labelsNamedLikeARegisterAreNotOperandNames() {
        byte[] image = {0x0E, 0x00, 0x03, 0x4E, 0x01, 0x03, 0x2E, 0x02, 0x03}; // ASL $0300, LSR $0301, ROL $0302
        String text = roundTrip(image, 0x0200, List.of(new Symbol(0x0300, "a", true), new Symbol(0x0301, "X", true), new Symbol(0x0302, "y", true)));
        assertTrue(text.contains("ASL $0300 "), text);
    }

    @Test
    void aBranchAcrossTheWrapAtFFFF() {
        assertTrue(roundTrip(".defsection code, $FFF0\n.section code\nBNE $0005\n").contains("BNE $0005 "));
    }

    @Test
    void labelsBranchesAndALabelJustPastTheImage() {
        String text = roundTrip("""
                CR = 13
                        .defsection code, $0200
                        .section code
                start:  LDX #CR
                loop:   DEX
                        BNE loop
                        BEQ done
                        LDA CR
                        JSR start
                done:   JMP end
                end:
                """);
        assertTrue(text.startsWith(".defsection code, $0200\n.section code\nend = $020F\nstart:\n"), text);
        assertTrue(text.contains("LDX #$0D "), text);
        assertTrue(text.contains("LDA $0D "), text);
        assertTrue(text.contains("BEQ done "), text);
    }

    @Test
    void aLabelNamedLikeTheSectionRoundTrips() {
        String text = roundTrip("""
                        .defsection code, $0200
                        .section code
                code:   JMP code
                """);
        assertTrue(text.startsWith(".defsection code, $0200\n.section code\ncode:\n"), text);
        assertTrue(text.contains("JMP code "), text);
    }

    @Test
    void cheapLocalLabels() {
        String text = roundTrip("""
                        .defsection code, $0200
                        .section code
                main:   LDX #3
                @loop:  DEX
                        BNE @loop
                        BEQ @done
                @done:  JMP other
                other:  LDY #2
                @loop:  DEY
                        BNE @loop
                """);
        assertTrue(text.contains("BNE @loop "), text);
    }

    @Test
    void aCheapLocalLabelKeepsItsNameAcrossASwitchToData() {
        String text = roundTrip("""
                        .defsection code, $0200
                        .defsection data, $0280
                        .section code
                print:  ldx #0
                        .section data
                tbl:    .byte 1
                        .section code
                @loop:  inx
                        bne @loop
                        rts
                """);
        assertTrue(text.contains("BNE @loop "), text);
    }

    @Test
    void anIncludeInsideARoutineRoundTripsWithoutTheNameOfItsCheapLocalLabel() {
        Output out = assemble(Map.of("inc.s", "helper: rts\n", "t.s", """
                        .defsection code, $0200
                        .section code
                start:  ldx #1
                @loop:  dex
                        .include "inc.s"
                        bne @loop
                        rts
                """));
        assertArrayEquals(HexFormat.ofDelimiter(" ").parseHex("A2 01 CA 60 D0 FC 60"), out.binary());
        assertTrue(roundTrip(out).contains("BNE $0202 "), "the Disassembler scopes by address, and helper is in between");
    }

    @Test
    void aDataRangePastTheEndDoesNotLengthenTheLastInstruction() {
        roundTrip(new byte[] {(byte) 0xAD}, 0x0200, new SymbolFile.Contents(List.of(), List.of(new Data(0x0202, 1))));
    }

    @Test
    void aDataRangePastTheWrapDoesNotRunTheLastInstructionPastFFFF() {
        roundTrip(new byte[] {(byte) 0xAD}, 0xFFFF, new SymbolFile.Contents(List.of(), List.of(new Data(0x0001, 1))));
    }

    @Test
    void randomImagesWithRandomSymbolsAndDataRangesRoundTrip() {
        var random = new Random(60); // seeded, so a failure repeats
        for (int i = 0; i < 5000; i++) roundTripRandomImage(random);
    }

    private static void roundTripRandomImage(Random random) {
        int size = random.nextInt(65);
        int lastOrg = Math.min(0xFFFF, 0x10000 - size);
        int org = switch (random.nextInt(3)) {
            case 0 -> random.nextInt(0x100);
            case 1 -> lastOrg - random.nextInt(0x100); // branches and data wrap at $FFFF
            default -> random.nextInt(lastOrg + 1);
        };
        var names = new ArrayList<>(NAMES);
        Collections.shuffle(names, random);
        var symbols = new ArrayList<Symbol>();
        for (String name : names.subList(0, random.nextInt(names.size() + 1))) {
            int value = address(random, org, size);
            boolean label = random.nextInt(4) > 0;
            symbols.add(new Symbol(value, name, label));
            if (!label) continue;
            for (String cheap : List.of("@loop", "@a"))
                if (random.nextBoolean()) symbols.add(new Symbol(value + random.nextInt(-2, 16), name + cheap, true));
        }
        Collections.shuffle(symbols, random);

        var image = new byte[size];
        for (int at = 0; at < size; ) {
            int op = random.nextInt(0x100);
            image[at++] = (byte) op;
            Opcode opcode = Opcodes.get(op);
            if (opcode == null) continue;
            // mostly a symbol's value, or operands would almost never have a name
            int operand = symbols.isEmpty() || random.nextInt(4) == 0 ? address(random, org, size)
                    : symbols.get(random.nextInt(symbols.size())).value();
            if (opcode.mode() == Mode.REL) operand -= org + at + 1;
            for (int b = 0; b < opcode.mode().operandSize() && at < size; b++) image[at++] = (byte) (operand >> (8 * b));
        }

        var data = new ArrayList<Data>();
        for (int n = random.nextInt(3); n > 0; n--) data.add(new Data(address(random, org, size) & 0xFFFF, random.nextInt(1, 17)));
        roundTrip(image, org, new SymbolFile.Contents(symbols, data));
    }

    /** An address in the image or just past it, in zero page, or anywhere. */
    private static int address(Random random, int org, int size) {
        return switch (random.nextInt(4)) {
            case 0, 1 -> org + random.nextInt(size + 1);
            case 2 -> random.nextInt(0x100);
            default -> random.nextInt(0x10000);
        };
    }
}
