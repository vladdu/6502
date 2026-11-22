package cpu;

import static cpu.Mnemonic.*;
import static cpu.Mode.*;

/** The 256-entry NMOS 6502 opcode table, shared by the CPU, Assembler and Disassembler. */
public final class Opcodes {
    private static final Opcode[] TABLE = new Opcode[256];

    private Opcodes() {}

    /** The documented opcode for this byte, or null if undocumented. */
    public static Opcode get(int opcode) {
        return TABLE[opcode & 0xFF];
    }

    private static void op(int code, Mnemonic mnemonic, Mode mode, int cycles) {
        TABLE[code] = new Opcode(mnemonic, mode, cycles, false);
    }

    /** Same, with the page-cross penalty. */
    private static void opx(int code, Mnemonic mnemonic, Mode mode, int cycles) {
        TABLE[code] = new Opcode(mnemonic, mode, cycles, true);
    }

    /** The eight-mode ALU group: ORA AND EOR ADC LDA CMP SBC. */
    private static void alu(Mnemonic m, int imm, int zp, int zpx, int abs, int abx, int aby, int izx, int izy) {
        op(imm, m, IMM, 2); op(zp, m, ZP, 3); op(zpx, m, ZPX, 4); op(abs, m, ABS, 4);
        opx(abx, m, ABX, 4); opx(aby, m, ABY, 4); op(izx, m, IZX, 6); opx(izy, m, IZY, 5);
    }

    /** Read-modify-write shifts and rotates: ASL LSR ROL ROR. */
    private static void shift(Mnemonic m, int acc, int zp, int zpx, int abs, int abx) {
        op(acc, m, ACC, 2); op(zp, m, ZP, 5); op(zpx, m, ZPX, 6); op(abs, m, ABS, 6); op(abx, m, ABX, 7);
    }

    static {
        alu(ORA, 0x09, 0x05, 0x15, 0x0D, 0x1D, 0x19, 0x01, 0x11);
        alu(AND, 0x29, 0x25, 0x35, 0x2D, 0x3D, 0x39, 0x21, 0x31);
        alu(EOR, 0x49, 0x45, 0x55, 0x4D, 0x5D, 0x59, 0x41, 0x51);
        alu(ADC, 0x69, 0x65, 0x75, 0x6D, 0x7D, 0x79, 0x61, 0x71);
        alu(LDA, 0xA9, 0xA5, 0xB5, 0xAD, 0xBD, 0xB9, 0xA1, 0xB1);
        alu(CMP, 0xC9, 0xC5, 0xD5, 0xCD, 0xDD, 0xD9, 0xC1, 0xD1);
        alu(SBC, 0xE9, 0xE5, 0xF5, 0xED, 0xFD, 0xF9, 0xE1, 0xF1);

        op(0x85, STA, ZP, 3); op(0x95, STA, ZPX, 4); op(0x8D, STA, ABS, 4);
        op(0x9D, STA, ABX, 5); op(0x99, STA, ABY, 5); op(0x81, STA, IZX, 6); op(0x91, STA, IZY, 6);
        op(0x86, STX, ZP, 3); op(0x96, STX, ZPY, 4); op(0x8E, STX, ABS, 4);
        op(0x84, STY, ZP, 3); op(0x94, STY, ZPX, 4); op(0x8C, STY, ABS, 4);

        op(0xA2, LDX, IMM, 2); op(0xA6, LDX, ZP, 3); op(0xB6, LDX, ZPY, 4);
        op(0xAE, LDX, ABS, 4); opx(0xBE, LDX, ABY, 4);
        op(0xA0, LDY, IMM, 2); op(0xA4, LDY, ZP, 3); op(0xB4, LDY, ZPX, 4);
        op(0xAC, LDY, ABS, 4); opx(0xBC, LDY, ABX, 4);

        op(0xE0, CPX, IMM, 2); op(0xE4, CPX, ZP, 3); op(0xEC, CPX, ABS, 4);
        op(0xC0, CPY, IMM, 2); op(0xC4, CPY, ZP, 3); op(0xCC, CPY, ABS, 4);
        op(0x24, BIT, ZP, 3); op(0x2C, BIT, ABS, 4);

        shift(ASL, 0x0A, 0x06, 0x16, 0x0E, 0x1E);
        shift(LSR, 0x4A, 0x46, 0x56, 0x4E, 0x5E);
        shift(ROL, 0x2A, 0x26, 0x36, 0x2E, 0x3E);
        shift(ROR, 0x6A, 0x66, 0x76, 0x6E, 0x7E);
        op(0xE6, INC, ZP, 5); op(0xF6, INC, ZPX, 6); op(0xEE, INC, ABS, 6); op(0xFE, INC, ABX, 7);
        op(0xC6, DEC, ZP, 5); op(0xD6, DEC, ZPX, 6); op(0xCE, DEC, ABS, 6); op(0xDE, DEC, ABX, 7);

        op(0x10, BPL, REL, 2); op(0x30, BMI, REL, 2); op(0x50, BVC, REL, 2); op(0x70, BVS, REL, 2);
        op(0x90, BCC, REL, 2); op(0xB0, BCS, REL, 2); op(0xD0, BNE, REL, 2); op(0xF0, BEQ, REL, 2);

        op(0x4C, JMP, ABS, 3); op(0x6C, JMP, IND, 5); op(0x20, JSR, ABS, 6);
        op(0x60, RTS, IMP, 6); op(0x40, RTI, IMP, 6); op(0x00, BRK, IMP, 7);
        op(0x48, PHA, IMP, 3); op(0x08, PHP, IMP, 3); op(0x68, PLA, IMP, 4); op(0x28, PLP, IMP, 4);

        op(0x18, CLC, IMP, 2); op(0x38, SEC, IMP, 2); op(0x58, CLI, IMP, 2); op(0x78, SEI, IMP, 2);
        op(0xB8, CLV, IMP, 2); op(0xD8, CLD, IMP, 2); op(0xF8, SED, IMP, 2);
        op(0xAA, TAX, IMP, 2); op(0xA8, TAY, IMP, 2); op(0x8A, TXA, IMP, 2); op(0x98, TYA, IMP, 2);
        op(0xBA, TSX, IMP, 2); op(0x9A, TXS, IMP, 2);
        op(0xE8, INX, IMP, 2); op(0xC8, INY, IMP, 2); op(0xCA, DEX, IMP, 2); op(0x88, DEY, IMP, 2);
        op(0xEA, NOP, IMP, 2);
    }
}
