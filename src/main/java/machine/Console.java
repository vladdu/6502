package machine;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.BlockingQueue;

/**
 * The serial-style character Device. Sees only a receive queue and an output stream.
 * The host puts received bytes into the queue, then {@link #EOF} once its input closes; EOF stays at the head for good.
 */
public final class Console implements Device {
    public static final int EOF = -1;
    private final BlockingQueue<Integer> in;
    private final OutputStream out;
    private int control;

    Console(BlockingQueue<Integer> in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public int read(int off) {
        return switch (off) {
            case 0 -> waiting() ? in.poll() : 0x00;
            case 1 -> 0x40 | (waiting() ? 0x80 : 0) | (closed() ? 0x20 : 0);
            case 2 -> control;
            default -> 0xFF;
        };
    }

    @Override
    public void write(int off, int v) {
        if (off == 0) transmit(v);
        else if (off == 2) control = v & 0x80;
    }

    /** Flushed per byte so output shows while the program runs. */
    private void transmit(int v) {
        try {
            out.write(v);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Type-ahead and EOF belong to the host, so only CONTROL clears. */
    @Override
    public void reset() {
        control = 0;
    }

    /** Pure level: no latch, no ack register. */
    @Override
    public boolean irq() {
        return control != 0 && waiting();
    }

    private boolean waiting() {
        Integer head = in.peek();
        return head != null && head != EOF;
    }

    private boolean closed() {
        Integer head = in.peek();
        return head != null && head == EOF;
    }

    @Override
    public int peek(int off) {
        return off == 0 ? (waiting() ? in.peek() : 0x00) : read(off);
    }
}
