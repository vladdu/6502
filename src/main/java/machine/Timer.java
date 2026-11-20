package machine;

/**
 * At $D100: a 16-bit countdown with reload that can raise IRQ, and a read-only 32-bit free-running cycle counter.
 * Registers and behaviour as decided in #7. Reads have no side effects.
 */
final class Timer implements Device {
    private static final int IRQ_ENABLE = 0x80, PERIODIC = 0x02, RUN = 0x01;

    private int control, reload, latchedCount, latchedCycles, cycles;
    private boolean flag;
    /** Live countdown; 1..65536 while running. */
    private int count;
    /** Run was just set: the Step that set it doesn't count. */
    private boolean starting;

    public int read(int off) {
        return switch (off) {
            case 0x01 -> (flag ? 0x80 : 0) | (control & RUN) << 6;
            case 0x02 -> control;
            case 0x04, 0x05 -> reload >> 8 * (off - 0x04) & 0xFF;
            case 0x06, 0x07 -> latchedCount >> 8 * (off - 0x06) & 0xFF;
            case 0x08, 0x09, 0x0A, 0x0B -> latchedCycles >>> 8 * (off - 0x08) & 0xFF;
            default -> 0xFF;
        };
    }

    public int peek(int off) { return read(off); }

    public void write(int off, int v) {
        switch (off) {
            case 0x00 -> {
                latchedCount = count & 0xFFFF;
                latchedCycles = cycles;
            }
            case 0x01 -> flag = false;
            case 0x02 -> {
                if ((v & RUN) != 0 && (control & RUN) == 0) {
                    count = period();
                    starting = true;
                }
                control = v & (IRQ_ENABLE | PERIODIC | RUN);
            }
            case 0x04 -> reload = reload & 0xFF00 | v;
            case 0x05 -> reload = reload & 0x00FF | v << 8;
            default -> {}
        }
    }

    public void tick(int c) {
        cycles += c; // wraps at 2^32
        if ((control & RUN) == 0) return;
        if (starting) {
            starting = false;
            return;
        }
        count -= c;
        if (count > 0) return;
        flag = true;
        if ((control & PERIODIC) != 0) {
            count = Math.floorMod(count - 1, period()) + 1; // overshoot carries; several periods set the flag once
        } else {
            control &= ~RUN;
            count = 0;
        }
    }

    public boolean irq() { return flag && (control & IRQ_ENABLE) != 0; }

    public void reset() {
        control = reload = latchedCount = latchedCycles = cycles = count = 0;
        flag = starting = false;
    }

    private int period() { return reload == 0 ? 0x10000 : reload; }
}
