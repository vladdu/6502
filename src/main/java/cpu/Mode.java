package cpu;

/** 6502 addressing modes. */
public enum Mode {
    IMP, // implied
    ACC, // accumulator: ASL A
    IMM, // #$nn
    ZP,  // $nn
    ZPX, // $nn,X
    ZPY, // $nn,Y
    ABS, // $nnnn
    ABX, // $nnnn,X
    ABY, // $nnnn,Y
    IND, // ($nnnn), JMP only
    IZX, // ($nn,X)
    IZY, // ($nn),Y
    REL; // branch offset

    /** Operand bytes after the opcode; the Assembler and Disassembler share this so they can't disagree. */
    public int operandSize() {
        return switch (this) {
            case IMP, ACC -> 0;
            case IMM, ZP, ZPX, ZPY, IZX, IZY, REL -> 1;
            case ABS, ABX, ABY, IND -> 2;
        };
    }
}
