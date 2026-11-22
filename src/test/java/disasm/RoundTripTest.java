package disasm;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import asm.Assembler.Errors;
import asm.Assembler.Output;
import disasm.SymbolFile.Symbol;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import testdata.TestData;

/** Assemble, disassemble, reassemble: the bytes must not change. */
class RoundTripTest {
    private static Output assemble(String src) {
        try {
            var r = Assembler.assemble(Path.of("t.s"), _ -> src.getBytes(UTF_8));
            if (r instanceof Errors(var messages)) fail(String.join("\n", messages) + "\nin:\n" + src);
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
        assertEquals(org, again.origin());
        assertArrayEquals(image, again.binary(), text);
        return text;
    }

    @Test
    void dormannFunctionalImage() throws IOException {
        roundTrip(Files.readAllBytes(TestData.dormannFunctional()), 0x0000, List.of());
    }

    /** Assembles {@code src}, round-trips its binary with its own symbol file; returns the disassembly. */
    private static String roundTrip(String src) {
        Output out = assemble(src);
        try {
            return roundTrip(out.binary(), out.origin(), SymbolFile.parse("t.sym", out.symbols()));
        } catch (SymbolFileException e) {
            return fail(e);
        }
    }

    @Test
    void dataFromDirectivesStaysData() {
        String text = roundTrip("""
                *= $0200
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
                *= $0200
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
                *= $0010
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
        assertTrue(roundTrip("*= $FFF0\nBNE $0005\n").contains("BNE $0005 "));
    }

    @Test
    void labelsBranchesAndALabelJustPastTheImage() {
        String text = roundTrip("""
                CR = 13
                        *= $0200
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
                        *= $0200
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
}
