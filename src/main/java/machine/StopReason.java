package machine;

/** Why {@link Machine#run} returned. */
public sealed interface StopReason {
    record Breakpoint(int pc) implements StopReason {
        @Override public String toString() { return String.format("breakpoint at $%04X", pc); }
    }

    record Exited(int code) implements StopReason {}

    record Illegal(int pc, int opcode) implements StopReason {
        @Override public String toString() { return String.format("illegal opcode $%02X at $%04X", opcode, pc); }
    }

    record UserBreak() implements StopReason {
        @Override public String toString() { return "user break"; }
    }
}
