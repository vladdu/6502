package machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Throttling against wall time; only lower bounds are asserted (pacing can be late, never early). */
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PacingTest {
    private final Machine m = new Machine();
    private long stopAt, stallAt = Long.MAX_VALUE;

    PacingTest() throws LoadException {
        m.loadProgram(new byte[] {0x4C, 0x00, 0x02}, 0x0200); // JMP $0200, 3 cycles a Step
        m.map(0xD400, 1, new Device() { // test-only: stops a run after a cycle count, or stalls the host once
            public int read(int off) { return 0; }
            public void write(int off, int v) {}
            public int peek(int off) { return 0; }
            public void tick(int cycles) {
                if (m.cycles >= stopAt) m.breakRequested = true;
                if (m.cycles >= stallAt) {
                    stallAt = Long.MAX_VALUE;
                    sleep(50);
                }
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /** Runs about {@code n} cycles, returns the wall time in ms. */
    private double run(long n) {
        stopAt = m.cycles + n;
        long t = System.nanoTime();
        assertEquals(new StopReason.UserBreak(), m.run(Set.of()));
        return (System.nanoTime() - t) / 1e6;
    }

    @Test
    void unthrottledByDefault() {
        assertEquals(0, m.hz);
    }

    @Test
    void aRunAfterAPauseShorterThanTheDebtLimitStillStartsAfreshWithNoBurst() {
        m.hz = 1_000_000;
        run(50_000);
        sleep(15);

        assertTrue(run(100_000) >= 95, "100,000 cycles at 1 MHz took under 95 ms");
    }

    @Test
    void aHostStallOfMoreThan20MsRebasesInsteadOfCatchingUp() {
        m.hz = 1_000_000;
        stallAt = m.cycles + 50_000;

        assertTrue(run(100_000) >= 140, "a 50 ms stall was caught up with a burst");
    }
}
