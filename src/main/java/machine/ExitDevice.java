package machine;

import java.util.function.IntConsumer;

/** At $D300: a write stops the Simulator with the byte as exit code. Reads return $FF. */
final class ExitDevice implements Device {
    private final IntConsumer onExit;

    ExitDevice(IntConsumer onExit) {
        this.onExit = onExit;
    }

    public int read(int off) { return 0xFF; }
    public int peek(int off) { return 0xFF; }
    public void write(int off, int v) { onExit.accept(v & 0xFF); }
}
