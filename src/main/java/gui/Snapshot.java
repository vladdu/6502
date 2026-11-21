package gui;

import disasm.SymbolTable;
import java.util.List;
import machine.DeviceState;
import machine.Machine;
import machine.Pins;
import machine.StopReason;
import machine.Watchpoint;

/**
 * One consistent Machine state for the panes, built on the Machine thread between Steps and never changed once published.
 *
 * @param id publication order, from 1
 * @param pins the sources asserting IRQ and NMI
 * @param running a run is going on; else stopped at {@code stop}
 * @param stop the last Stop, null for Paused at the start
 * @param stopText the reason line of the last Stop display, or "Machine thread bug"
 * @param devices every Device's state, in mapping order
 * @param breakpoints ascending
 * @param watchpoints by start address
 * @param memory all 64K via peek
 * @param symbols a copy of the Symbol table, shared by snapshots until it changes
 * @param baseline the id of the snapshot taken just before the last run (before any run, the first), the one stopped change highlights compare with
 * @param baselineMemory that snapshot's memory
 * @param areas the Memory map
 */
@SuppressWarnings("ArrayRecordComponent") // never compared or hashed; each array is filled once, before publishing
public record Snapshot(
        long id,
        int pc, int a, int x, int y, int s, int p,
        Pins pins, long cycles, long steps,
        boolean running, StopReason stop, String stopText,
        List<DeviceState> devices, List<Integer> breakpoints, List<Watchpoint> watchpoints,
        byte[] memory, SymbolTable symbols,
        long baseline, byte[] baselineMemory, List<Machine.Area> areas) {}
