package machine;

import java.util.function.IntConsumer;

/** A write to offset 0 stops the Simulator with the byte as exit code; the rest of its page is unused. Reads return $FF. */
final class ExitDevice implements Device {
    private final IntConsumer onExit;

    ExitDevice(IntConsumer onExit) {
        this.onExit = onExit;
    }

    public int read(int off) { return 0xFF; }
    public int peek(int off) { return 0xFF; }
    public void write(int off, int v) { if (off == 0) onExit.accept(v & 0xFF); }
}
