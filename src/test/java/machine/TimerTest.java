package machine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The Timer at $D100, driven through the Machine by known-cycle programs. */
class TimerTest {
    private static final int LATCH = 0xD100, STATUS = 0xD101, CONTROL = 0xD102, RELOAD = 0xD104, COUNT = 0xD106,
            CYCLES = 0xD108;
    private final Machine m = new Machine();

    /** Assembles {@code body} at $0200 (with TIMER, LATCH... defined) and loads it in Program mode. */
    private void load(String body) throws IOException, LoadException {
        String src = """
                LATCH   = $D100
                STATUS  = $D101
                CONTROL = $D102
                RELOAD  = $D104
                        .defsection code, $0200
                        .section code
                """ + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), _ -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        m.loadProgram(((Assembler.Output) r).binary(), 0x0200);
    }

    /** Steps until PC reaches {@code pc}. */
    private void runTo(int pc) {
        while (m.cpu.pc != pc) m.step();
    }

    private void steps(int n) {
        for (int i = 0; i < n; i++) m.step();
    }

    /** Latches (outside any Step) and returns the latched COUNT. */
    private int latchedCount() {
        m.bus.write(LATCH, 0);
        return word(COUNT);
    }

    private int word(int a) {
        return m.bus.read(a) | m.bus.read(a + 1) << 8;
    }

    private boolean flag() {
        return (m.bus.read(STATUS) & 0x80) != 0;
    }

    /** Starting code, 15 bytes ending at $020E: RELOAD = n, then CONTROL = control. */
    private static String start(int n, int control) {
        return String.format("""
                        LDA #<%d
                        STA RELOAD
                        LDA #>%d
                        STA RELOAD+1
                        LDA #$%02X
                        STA CONTROL
                """, n, n, control);
    }

    @Test
    void periodicFlagRisesExactlyEveryNCyclesWithNoDrift() throws Exception {
        load(start(5, 0x02 | 0x01) + " .res 12, $EA"); // NOPs, 2 cycles each
        runTo(0x020F); // past the starting STA, whose cycles don't count
        List<Integer> risen = new ArrayList<>();
        for (int nop = 1; nop <= 12; nop++) {
            m.step();
            if (flag()) {
                risen.add(nop);
                m.bus.write(STATUS, 0x5A); // any value acknowledges
            }
        }
        // crossings at 5, 10, 15, 20, 25 cycles: NOP boundaries 6, 10, 16, 20, 26 -> NOPs 3, 5, 8, 10, 13
        assertEquals(List.of(3, 5, 8, 10), risen);
        assertEquals(0x40, m.bus.read(STATUS)); // still running
    }

    @Test
    void oneShotUnderflowSetsTheFlagClearsRunAndStaysAtZero() throws Exception {
        load(start(4, 0x01) + " .res 6, $EA");
        runTo(0x020F);
        m.step();
        assertFalse(flag());
        m.step(); // 4 cycles counted
        assertEquals(0x80, m.bus.read(STATUS)); // flag set, no longer running
        assertEquals(0x00, m.bus.read(CONTROL));
        assertFalse(m.cpu.irqLine); // IRQ enable is clear
        assertEquals(0, latchedCount());
        m.bus.write(STATUS, 0);
        steps(3);
        assertEquals(0x00, m.bus.read(STATUS));
        assertEquals(0, latchedCount());
    }

    @Test
    void reloadWrittenWhileRunningAffectsOnlyTheNextReload() throws Exception {
        load(start(10, 0x03) + """
                        NOP             ; 10 -> 8
                        LDA #4          ; -> 6
                        STA RELOAD      ; -> 2
                        NOP             ; -> 0: flag, reload 4
                        NOP
                        NOP             ; flag again, 4 cycles later
                """);
        runTo(0x020F);
        steps(3);
        assertEquals(2, latchedCount());
        assertFalse(flag());
        m.step();
        assertTrue(flag());
        m.bus.write(STATUS, 0);
        assertEquals(4, latchedCount());
        m.step();
        assertFalse(flag());
        m.step();
        assertTrue(flag());
    }

    @Test
    void severalPeriodsInOneStepSetTheFlagOnceAndTheCountWraps() throws Exception {
        load(start(3, 0x03) + """
                        INC $0300,X     ; 7 cycles: crosses 0 at 3 and 6, then 3 -> 2
                        NOP             ; -> 0: flag
                """);
        runTo(0x020F);
        m.step();
        assertTrue(flag());
        assertEquals(2, latchedCount());
        m.bus.write(STATUS, 0);
        m.step();
        assertTrue(flag());
        assertEquals(3, latchedCount());
    }

    @Test
    void clearingRunFreezesSettingItAgainReloadsAndRewritingRunDoesNothing() throws Exception {
        load(start(10, 0x03) + """
                        NOP             ; 10 -> 8
                        STA CONTROL     ; run=1 again while running: -> 4, no reload
                        LDA #$02
                        STA CONTROL     ; LDA -> 2, then run cleared: frozen
                        NOP
                        NOP
                        LDA #$03
                        STA CONTROL     ; reload 10; this Step doesn't count
                        NOP             ; -> 8
                """);
        runTo(0x020F);
        steps(2);
        assertEquals(4, latchedCount());
        steps(5);
        assertEquals(2, latchedCount());
        assertEquals(0x00, m.bus.read(STATUS));
        m.step();
        assertEquals(10, latchedCount());
        assertEquals(0x40, m.bus.read(STATUS));
        m.step();
        assertEquals(8, latchedCount());
    }

    @Test
    void reloadZeroMeans65536() throws Exception {
        load(start(0, 0x03) + " NOP");
        runTo(0x020F);
        m.step();
        assertEquals(0xFFFE, latchedCount());
    }

    @Test
    void latchGivesConsistentCountAndCyclesInAnyReadOrder() throws Exception {
        load(start(1000, 0x03) + " .res 30, $EA");
        runTo(0x020F); // 18 cycles of setup
        steps(10);
        m.bus.write(LATCH, 0x77);
        steps(10);
        int cyclesHiFirst = m.bus.read(CYCLES + 3) << 24 | m.bus.read(CYCLES + 2) << 16 | m.bus.read(CYCLES + 1) << 8
                | m.bus.read(CYCLES);
        int countHiFirst = m.bus.read(COUNT + 1) << 8 | m.bus.read(COUNT);
        steps(5);
        assertEquals(38, cyclesHiFirst);
        assertEquals(980, countHiFirst);
        assertEquals(38, m.bus.read(CYCLES) | m.bus.read(CYCLES + 1) << 8 | m.bus.read(CYCLES + 2) << 16
                | m.bus.read(CYCLES + 3) << 24);
        assertEquals(980, word(COUNT));
        assertEquals(950, latchedCount());
        assertEquals(68, word(CYCLES));
    }

    @Test
    void registersReadAsTheTableSaysAndPeekEqualsRead() {
        m.bus.write(RELOAD, 0x34);
        m.bus.write(RELOAD + 1, 0x12);
        m.bus.write(CONTROL, 0xFF);
        assertEquals(0x1234, word(RELOAD));
        assertEquals(0x83, m.bus.read(CONTROL));
        assertEquals(0x40, m.bus.read(STATUS));
        for (int off : new int[] {0x00, 0x03, 0x0C, 0x80, 0xFF}) {
            m.bus.write(0xD100 + off, 0);
            assertEquals(0xFF, m.bus.read(0xD100 + off), String.format("offset $%02X", off));
        }
        m.bus.write(LATCH, 0);
        for (int off = 0; off < 0x100; off++) assertEquals(m.bus.read(0xD100 + off), m.bus.peek(0xD100 + off));
        assertEquals(0x1234, word(COUNT)); // reads changed nothing
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // an unacknowledged IRQ re-enters forever
    void demoTakesPeriodicIrqsAcknowledgesAndExitsWithTheTickCount() throws Exception {
        load("""
                        LDA #<irq
                        STA $FFFE
                        LDA #>irq
                        STA $FFFF
                        LDA #<1000
                        STA RELOAD
                        LDA #>1000
                        STA RELOAD+1
                        LDA #$83        ; IRQ enable, periodic, run
                        STA CONTROL
                        CLI
                wait:   LDA ticks
                        CMP #100
                        BNE wait
                        STA $D300       ; exit with the tick count
                irq:    PHA
                        STA STATUS      ; acknowledge
                        INC ticks
                        PLA
                        RTI
                ticks:  .byte 0
                """);

        assertEquals(new StopReason.Exited(100), m.run(Set.of()));
        // one tick per 1000 cycles: an unacknowledged IRQ would re-enter at once and count far faster
        assertTrue(m.cycles >= 100_000 && m.cycles < 101_000, "cycles " + m.cycles);
        m.bus.write(LATCH, 0);
        assertEquals(m.cycles, word(CYCLES) | (long) word(CYCLES + 2) << 16); // every Step, interrupt entries too
    }

    @Test
    void resetZeroesAllRegistersAndCounters() throws Exception {
        load(start(4, 0x83) + " .res 4, $EA"); // I stays set: no IRQ taken
        steps(8);
        assertEquals(0xC0, m.bus.read(STATUS));
        m.bus.write(LATCH, 0);
        assertNotEquals(0, word(CYCLES));
        assertNotEquals(0, word(COUNT));

        m.reset();

        for (int a : new int[] {STATUS, CONTROL, RELOAD, RELOAD + 1, COUNT, COUNT + 1, CYCLES, CYCLES + 1, CYCLES + 2, CYCLES + 3})
            assertEquals(0, m.bus.read(a), String.format("$%04X", a));
        m.bus.write(LATCH, 0);
        assertEquals(0, word(COUNT));
        assertEquals(0, word(CYCLES));
    }

    @Test
    void describeShowsControlFlagAndReload() {
        var t = new Timer();
        assertEquals("Timer: stopped, one-shot, IRQ off, flag clear, reload $0000", t.state().describe());
        t.write(0x04, 0x10);
        t.write(0x02, 0x83);
        t.tick(1); // the starting Step doesn't count
        t.tick(0x10);
        assertEquals("Timer: running, periodic, IRQ on, flag set, reload $0010", t.state().describe());
    }

    @Test
    void stateShowsControlBitsReloadLiveCountFlagAndCycles() {
        var t = new Timer();
        t.write(0x04, 0x10);
        t.write(0x02, 0x83);
        t.tick(1); // the starting Step doesn't count
        t.tick(0x14); // underflow with 4 cycles over: count $10 - 4

        assertEquals(new DeviceState.Timer(true, true, true, true, 0x10, 0x0C, 0x15), t.state());
        t.reset();
        t.tick(-1); // the cycle counter wraps at 2^32 and reads unsigned
        assertEquals(new DeviceState.Timer(false, false, false, false, 0, 0, 0xFFFF_FFFFL), t.state());
    }
}
