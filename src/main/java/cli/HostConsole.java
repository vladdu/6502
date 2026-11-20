package cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import machine.Console;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

/**
 * The Console's host side while the Machine runs: the shared JLine Terminal in raw mode, and a daemon reader thread
 * feeding the Console's receive queue. Ctrl-] is never enqueued: it signals a break and parks the reader so the
 * Monitor can own the Terminal.
 */
final class HostConsole implements AutoCloseable {
    static final int CTRL_RIGHT_BRACKET = 0x1D;

    /** Opens the Terminal: the system one for the real CLI, a DumbTerminal over in-memory streams in tests. */
    interface Opener {
        Terminal open() throws IOException;
    }

    /** Silent dumb fallback when stdin isn't a TTY; no grapheme probe, whose late replies would arrive as input. */
    static Terminal system() throws IOException {
        return TerminalBuilder.builder().system(true).dumb(true).graphemeCluster(false).build();
    }

    final Terminal terminal;
    private final BlockingQueue<Integer> input;
    private final Runnable onBreak;
    private final Attributes saved;
    /** The reader parks on this after Ctrl-]; the Monitor releases it when the Machine runs again. */
    private final Semaphore resume = new Semaphore(0);
    private final Thread reader;
    private volatile boolean stopped;

    /** Enters raw mode and starts reading into {@code input} (UTF-8 bytes, then {@link Console#EOF}). */
    HostConsole(Terminal terminal, BlockingQueue<Integer> input, Runnable onBreak) {
        this.terminal = terminal;
        this.input = input;
        this.onBreak = onBreak;
        saved = terminal.enterRawMode();
        reader = Thread.ofPlatform().daemon().name("console-reader").start(this::read);
    }

    private void read() {
        NonBlockingReader r = terminal.reader();
        char high = 0; // pending high surrogate: a non-BMP char arrives as two reads
        try {
            while (!stopped) {
                int c = r.read(100); // timed only so a stop is noticed
                if (c == NonBlockingReader.READ_EXPIRED) continue;
                if (c == NonBlockingReader.EOF) {
                    input.put(Console.EOF);
                    return;
                }
                if (c == CTRL_RIGHT_BRACKET) {
                    onBreak.run();
                    resume.acquire();
                    continue;
                }
                if (Character.isHighSurrogate((char) c)) {
                    high = (char) c;
                    continue;
                }
                String s = high != 0 ? new String(new char[] {high, (char) c}) : String.valueOf((char) c);
                high = 0;
                for (byte b : s.getBytes(UTF_8)) input.put(b & 0xFF);
            }
        } catch (IOException | InterruptedException e) {
            // Terminal closed or reader stopped: no more input.
        }
    }

    /** Stops the reader (the Terminal throws once closed) and restores the Terminal's attributes; it stays open. */
    @Override
    public void close() {
        stopped = true;
        reader.interrupt(); // unparks it after Ctrl-]
        try {
            reader.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        terminal.setAttributes(saved);
    }
}
