package machine;

import cpu.Entry;

/** Why {@link Machine#run} returned: a Stop. */
public sealed interface StopReason {
    record Breakpoint(int pc) implements StopReason {
        @Override public String toString() { return String.format("breakpoint at $%04X", pc); }
    }

    record Exited(int code) implements StopReason {
        @Override public String toString() { return "exited with code " + code; }
    }

    record Illegal(int pc, int opcode) implements StopReason {
        @Override public String toString() { return String.format("illegal opcode $%02X at $%04X", opcode, pc); }
    }

    /** An interrupt entry found $0000 in {@code vector}; see the glossary's Stray interrupt. */
    record StrayInterrupt(int vector) implements StopReason {
        @Override public String toString() { return String.format("stray interrupt: vector $%04X = $0000", vector); }
    }

    /** The Step limit ran out; {@code entry} is the interrupt entry the last Step made, null when none. */
    record Stepped(Entry entry, int pc) implements StopReason {
        @Override public String toString() { return entry == null ? "step" : String.format("%s entry -> $%04X", entry, pc); }
    }

    /** A Watchpoint hit: the Step's first ({@code old == value} for a read), {@code by} an instruction, an interrupt entry or Disk, and {@code more} after it. */
    record Watchpoint(int address, boolean write, int old, int value, String by, int more) implements StopReason {
        @Override public String toString() {
            return String.format("watchpoint: %s $%04X %s by %s%s", write ? "write" : "read", address,
                    write ? String.format("$%02X->$%02X", old, value) : String.format("$%02X", value),
                    by, more == 0 ? "" : " (+" + more + " more)");
        }
    }

    record UserBreak() implements StopReason {
        @Override public String toString() { return "user break"; }
    }
}
