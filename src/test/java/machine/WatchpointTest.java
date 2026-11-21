package machine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;

/** Watchpoints on the memory map: what counts as a hit and the Stop it makes. */
class WatchpointTest {
    private final BlockingQueue<Integer> in = new LinkedBlockingQueue<>();
    private final Machine m = new Machine(in, OutputStream.nullOutputStream());

    private void load(int... b) throws LoadException {
        var out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        m.loadProgram(out, 0x0200);
    }

    private static Watchpoint writes(int start, int length) {
        return new Watchpoint(start, length, false, true);
    }

    private static Watchpoint reads(int start, int length) {
        return new Watchpoint(start, length, true, false);
    }

    @Test
    void aWriteInsideTheRangeStopsAfterItsStepWithOldAndNewValue() throws LoadException {
        load(0xA9, 0x13,       // LDA #$13
             0x8D, 0x02, 0x03, // STA $0302
             0xEA);            // NOP
        m.write(0x0302, 0x12);
        m.watch(List.of(writes(0x0300, 4)));

        var stop = m.run(Set.of());

        assertEquals(new StopReason.Watchpoint(0x0302, true, 0x12, 0x13, "$0202", 0), stop);
        assertEquals("watchpoint: write $0302 $12->$13 by $0202", stop.toString());
        assertEquals(0x0205, m.cpu.pc);
        assertEquals(0x13, m.bus.peek(0x0302));
    }

    @Test
    void aWriteWatchIgnoresReadsAndAReadWatchIgnoresWrites() throws LoadException {
        load(0xAD, 0x00, 0x03, // LDA $0300
             0x8D, 0x00, 0x03, // STA $0300
             0xAD, 0x00, 0x03, // LDA $0300
             0x8D, 0x00, 0xD3); // STA $D300
        m.watch(List.of(writes(0x0300, 1)));
        assertEquals(new StopReason.Watchpoint(0x0300, true, 0, 0, "$0203", 0), m.run(Set.of()));

        m.watch(List.of(reads(0x0300, 1)));
        var stop = m.run(Set.of());
        assertEquals(new StopReason.Watchpoint(0x0300, false, 0, 0, "$0206", 0), stop);
        assertEquals("watchpoint: read $0300 $00 by $0206", stop.toString());

        m.watch(List.of());
        assertEquals(new StopReason.Exited(0), m.run(Set.of()));
    }

    @Test
    void readWriteWatchCountsBothAccessesOfAReadModifyWriteAndReportsTheFirst() throws LoadException {
        load(0xEE, 0x00, 0x03); // INC $0300
        m.write(0x0300, 0x05);
        m.watch(List.of(new Watchpoint(0x0300, 1, true, true)));

        var stop = m.run(Set.of());

        assertEquals(new StopReason.Watchpoint(0x0300, false, 0x05, 0x05, "$0200", 1), stop);
        assertEquals("watchpoint: read $0300 $05 by $0200 (+1 more)", stop.toString());
    }

    @Test
    void anOpcodeFetchHitsAReadWatchOverCode() throws LoadException {
        load(0xEA, 0xEA, 0xEA); // NOP; NOP; NOP
        m.watch(List.of(reads(0x0201, 1)));

        assertEquals(new StopReason.Watchpoint(0x0201, false, 0xEA, 0xEA, "$0201", 0), m.run(Set.of()));
        assertEquals(0x0202, m.cpu.pc);
    }

    @Test
    void anInterruptEntrysStackPushesAreByTheEntry() throws LoadException {
        var pin = new Device() {
            @Override
            public int read(int off) { return 0; }
            @Override
            public void write(int off, int v) {}
            @Override
            public int peek(int off) { return 0; }
            @Override
            public boolean nmi() { return true; }
        };
        m.map(0xD600, 0x100, pin);
        load(0xEA, 0xEA); // NOP; NOP
        m.write(0xFFFA, 0x00);
        m.write(0xFFFB, 0x03);
        m.watch(List.of(writes(0x01FB, 3)));

        assertEquals(new StopReason.Watchpoint(0x01FD, true, 0, 0x02, "NMI entry", 2), m.run(Set.of()));
        assertEquals(0x0300, m.cpu.pc);
    }

    @Test
    void theStepCompletesSoAWatchedConsoleReadHasTakenItsByte() throws LoadException {
        load(0xAD, 0x00, 0xD0); // LDA $D000
        in.add(0x41);
        m.watch(List.of(reads(0xD000, 1)));

        assertEquals(new StopReason.Watchpoint(0xD000, false, 0x41, 0x41, "$0200", 0), m.run(Set.of()));
        assertEquals(0x41, m.cpu.a);
        assertTrue(in.isEmpty());
    }

    @Test
    void peekAndMonitorWritesNeverHit() throws LoadException {
        load(0x8D, 0x00, 0xD3); // STA $D300
        m.watch(List.of(new Watchpoint(0x0300, 1, true, true)));

        m.bus.peek(0x0300);
        m.write(0x0300, 0x42);

        assertEquals(new StopReason.Exited(0), m.run(Set.of()));
        assertEquals(0x42, m.bus.peek(0x0300));
    }

    @Test
    void watchpointsSurviveReset() throws LoadException {
        load(0x8D, 0x00, 0x03); // STA $0300
        m.write(0xFFFC, 0x00);
        m.write(0xFFFD, 0x02);
        m.watch(List.of(writes(0x0300, 1)));

        m.reset();

        assertInstanceOf(StopReason.Watchpoint.class, m.run(Set.of()));
    }

    @Test
    void anIllegalOpcodeStopsAsIllegalEvenWhenItsFetchHit() throws LoadException {
        load(0x02,              // undocumented
             0xEA,              // NOP
             0x8D, 0x00, 0xD3); // STA $D300
        m.watch(List.of(reads(0x0200, 1)));

        assertEquals(new StopReason.Illegal(0x0200, 0x02), m.run(Set.of()));
        m.cpu.pc = 0x0201; // the illegal Step's hit is not carried into the next Step
        assertEquals(new StopReason.Exited(0), m.run(Set.of()));
    }
}
