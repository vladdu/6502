package disasm;

import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolFile.Symbol;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisassemblerTest {
    private static byte[] bytes(int... b) {
        var out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    @Test
    void sourceFormStartsWithOriginAndIndentsInstructionsWithAddressAndBytes() {
        assertEquals("""
                *= $0200
                        LDA #$2A                ; 0200  A9 2A
                        STA $D300               ; 0202  8D 00 D3
                        ASL A                   ; 0205  0A
                        RTS                     ; 0206  60
                """, Disassembler.source(bytes(0xA9, 0x2A, 0x8D, 0x00, 0xD3, 0x0A, 0x60), 0x0200, List.of()));
    }

    @Test
    void operandsInEveryModeWithBranchTargetsAbsolute() {
        assertEquals("""
                *= $0200
                        LDA $12                 ; 0200  A5 12
                        LDA $12,X               ; 0202  B5 12
                        LDX $12,Y               ; 0204  B6 12
                        LDA ($12,X)             ; 0206  A1 12
                        LDA ($12),Y             ; 0208  B1 12
                        LDA $1234,X             ; 020A  BD 34 12
                        LDA $1234,Y             ; 020D  B9 34 12
                        JMP ($1234)             ; 0210  6C 34 12
                        BNE $0213               ; 0213  D0 FE
                        BPL $0219               ; 0215  10 02
                        BMI $01F9               ; 0217  30 E0
                """, Disassembler.source(bytes(0xA5, 0x12, 0xB5, 0x12, 0xB6, 0x12, 0xA1, 0x12, 0xB1, 0x12,
                0xBD, 0x34, 0x12, 0xB9, 0x34, 0x12, 0x6C, 0x34, 0x12, 0xD0, 0xFE, 0x10, 0x02, 0x30, 0xE0), 0x0200, List.of()));
    }

    @Test
    void undocumentedOpcodesAndATrailingPartialInstructionAreBytes() {
        assertEquals("""
                *= $0200
                        .byte $02               ; 0200  02  undocumented
                        NOP                     ; 0201  EA
                        .byte $AD, $34          ; 0202  AD 34
                """, Disassembler.source(bytes(0x02, 0xEA, 0xAD, 0x34), 0x0200, List.of()));
    }

    @Test
    void decodeWrapsAtTheTopOfMemory() {
        var in = Disassembler.decode(a -> a == 0xFFFF ? 0xAD : a == 0 ? 0x34 : 0x12, 0xFFFF);
        assertEquals(0xFFFF, in.addr());
        assertEquals("LDA", in.mnemonic());
        assertEquals(0x1234, in.operand());
        assertArrayEquals(new int[] {0xAD, 0x34, 0x12}, in.bytes());
    }

    @Test
    void labelsNameAddressOperandsButNeverConstsOrImmediates() {
        var symbols = List.of(new Symbol(0x000D, "CR", false), new Symbol(0x0010, "zp", true), new Symbol(0x0200, "start", true),
                new Symbol(0x0200, "alias", true), new Symbol(0x0202, "loop", true), new Symbol(0xD300, "out", true),
                new Symbol(0xE000, "unused", true));
        assertEquals("""
                *= $0200
                zp = $0010
                out = $D300
                start:
                alias:
                        LDX #$0D                ; 0200  A2 0D
                loop:
                        DEX                     ; 0202  CA
                        BNE loop                ; 0203  D0 FD
                        STA out                 ; 0205  8D 00 D3
                        LDA $0D                 ; 0208  A5 0D
                        LDA zp                  ; 020A  A5 10
                        LDA a:zp                ; 020C  AD 10 00
                        JMP start               ; 020F  4C 00 02
                        LDA #$10                ; 0212  A9 10
                """, Disassembler.source(bytes(0xA2, 0x0D, 0xCA, 0xD0, 0xFD, 0x8D, 0x00, 0xD3, 0xA5, 0x0D, 0xA5, 0x10,
                0xAD, 0x10, 0x00, 0x4C, 0x00, 0x02, 0xA9, 0x10), 0x0200, symbols));
    }

    @Test
    void decodingRestartsAtALabelInsideAnInstruction() {
        assertEquals("""
                *= $0200
                        .byte $AD               ; 0200  AD
                mid:
                        NOP                     ; 0201  EA
                        .byte $20, $EA          ; 0202  20 EA
                end:
                        NOP                     ; 0204  EA
                """, Disassembler.source(bytes(0xAD, 0xEA, 0x20, 0xEA, 0xEA), 0x0200,
                List.of(new Symbol(0x0201, "mid", true), new Symbol(0x0204, "end", true))));
    }

    @Test
    void cheapLocalLabelsOnlyInsideTheirParentsScope() {
        var symbols = List.of(new Symbol(0x0200, "main", true), new Symbol(0x0202, "main@loop", true),
                new Symbol(0x0205, "other", true), new Symbol(0x0206, "main@far", true), new Symbol(0x0208, "other@loop", true),
                new Symbol(0x020A, "gone@x", true), new Symbol(0xD000, "gone@io", true));
        assertEquals("""
                *= $0200
                main:
                        LDX #$03                ; 0200  A2 03
                @loop:
                        DEX                     ; 0202  CA
                        BNE @loop               ; 0203  D0 FD
                other:
                        JMP $0202               ; 0205  4C 02 02
                @loop:
                        BNE @loop               ; 0208  D0 FE
                        JMP $020A               ; 020A  4C 0A 02
                        STA $D000               ; 020D  8D 00 D0
                """, Disassembler.source(bytes(0xA2, 0x03, 0xCA, 0xD0, 0xFD, 0x4C, 0x02, 0x02, 0xD0, 0xFE, 0x4C, 0x0A, 0x02,
                0x8D, 0x00, 0xD0), 0x0200, symbols));
    }
}
