package cpu;

/** Thrown by {@link Cpu#step()} on an undocumented opcode; PC is left on the opcode, no state changed. */
public final class IllegalOpcode extends RuntimeException {
    public final int pc, opcode;

    public IllegalOpcode(int pc, int opcode) {
        super(String.format("illegal opcode $%02X at $%04X", opcode, pc));
        this.pc = pc;
        this.opcode = opcode;
    }
}
