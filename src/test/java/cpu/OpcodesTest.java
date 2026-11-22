package cpu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OpcodesTest {

    // Standard NMOS 6502 opcode matrix: row = high nibble, column = low nibble, x = documented.
    private static final String[] DOCUMENTED = {
        "xx...xx.xxx..xx.", // 0
        "xx...xx.xx...xx.", // 1
        "xx..xxx.xxx.xxx.", // 2
        "xx...xx.xx...xx.", // 3
        "xx...xx.xxx.xxx.", // 4
        "xx...xx.xx...xx.", // 5
        "xx...xx.xxx.xxx.", // 6
        "xx...xx.xx...xx.", // 7
        ".x..xxx.x.x.xxx.", // 8
        "xx..xxx.xxx..x..", // 9
        "xxx.xxx.xxx.xxx.", // A
        "xx..xxx.xxx.xxx.", // B
        "xx..xxx.xxx.xxx.", // C
        "xx...xx.xx...xx.", // D
        "xx..xxx.xxx.xxx.", // E
        "xx...xx.xx...xx.", // F
    };

    @Test
    void listsExactlyThe151DocumentedOpcodes() {
        int count = 0;
        for (int op = 0; op < 256; op++) {
            boolean documented = DOCUMENTED[op >> 4].charAt(op & 0xF) == 'x';
            assertEquals(documented, Opcodes.get(op) != null, String.format("opcode $%02X", op));
            if (documented) count++;
        }
        assertEquals(151, count);
    }

    @Test
    void entriesCarryMnemonicModeCyclesAndPageCrossPenalty() {
        assertEquals(new Opcode(Mnemonic.LDA, Mode.ABX, 4, true), Opcodes.get(0xBD));
        assertEquals(new Opcode(Mnemonic.STA, Mode.ABX, 5, false), Opcodes.get(0x9D));
        assertEquals(new Opcode(Mnemonic.LDA, Mode.IZY, 5, true), Opcodes.get(0xB1));
        assertEquals(new Opcode(Mnemonic.JMP, Mode.IND, 5, false), Opcodes.get(0x6C));
        assertEquals(new Opcode(Mnemonic.ASL, Mode.ACC, 2, false), Opcodes.get(0x0A));
        assertEquals(new Opcode(Mnemonic.BRK, Mode.IMP, 7, false), Opcodes.get(0x00));
        assertEquals(new Opcode(Mnemonic.LDX, Mode.ZPY, 4, false), Opcodes.get(0xB6));
        assertEquals(new Opcode(Mnemonic.BNE, Mode.REL, 2, false), Opcodes.get(0xD0));
        assertEquals(new Opcode(Mnemonic.INC, Mode.ABX, 7, false), Opcodes.get(0xFE));
        assertNull(Opcodes.get(0xDB));
    }
}
