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
    REL, // branch offset
}
