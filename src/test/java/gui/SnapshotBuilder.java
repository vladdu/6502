package gui;

import disasm.SymbolTable;
import java.util.List;
import machine.Machine;
import machine.Pins;
import machine.Watchpoint;

/** Snapshots for pane-logic tests: a real Machine's Memory map, the fields set here, everything else zero. */
final class SnapshotBuilder {
    private static final List<Machine.Area> AREAS = new Machine().areas();
    long id = 1;
    int pc = 0x0200, s = 0xFD, previousPc = -1;
    boolean running;
    byte[] memory = new byte[0x10000];
    /** Null: the same as {@link #memory}. */
    byte[] baselineMemory;
    List<Integer> breakpoints = List.of();
    List<Watchpoint> watchpoints = List.of();
    SymbolTable symbols = new SymbolTable();

    Snapshot build() {
        return new Snapshot(id, pc, 0, 0, 0, s, 0x24, new Pins(List.of(), List.of()), 0, 0, running, null, "Paused",
                List.of(), breakpoints, watchpoints, memory, symbols, 0, baselineMemory == null ? memory : baselineMemory, AREAS,
                previousPc);
    }
}
