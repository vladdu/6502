package gui;

import cli.Interpreter;
import cli.Main;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import machine.Machine;
import machine.StopReason;

/**
 * The Machine thread: the only thread that touches the Machine and the Interpreter. Other threads post command lines and
 * breaks; stopped, it runs posted lines in order, and running, it runs them between Steps through the Machine's attention flag.
 */
public final class MachineThread {
    /** The commands that run between Steps without stopping: changes and queries. Every other command is refused while running. */
    private static final Set<String> WHILE_RUNNING = Set.of(
            "b",
            "w",
            "speed",
            "m",
            "reset",
            "nmi",
            "disk",
            "r",
            "d",
            "info",
            "help",
            "quit", "q");

    private final Machine machine;
    private final Interpreter interpreter;
    /** What the Interpreter printed, until {@link #output} takes it. */
    private final StringWriter transcript = new StringWriter();
    private final PrintWriter out = new PrintWriter(transcript);
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    /** Null until {@link #start}. */
    private volatile Thread thread;
    /** The session's exit code, once {@code quit} or {@link #close} ends it. */
    public final CompletableFuture<Integer> exit = new CompletableFuture<>();
    /** A Ctrl-] waiting for the running Machine. */
    private volatile boolean breakPending;
    /** Running a Step-executing command, else stopped at {@link #stop} (null: Paused at the start), or after a Machine thread bug. */
    private volatile boolean running, bug;
    private volatile StopReason stop;

    /** {@code interpreter} builds the Interpreter over the transcript and the hook to call just before each run. */
    public MachineThread(Machine machine, BiFunction<PrintWriter, Runnable, Interpreter> interpreter) {
        this.machine = machine;
        this.interpreter = interpreter.apply(out, this::resumed);
        machine.between = this::between;
    }

    /** Starts the thread: Paused, or running at once; {@code warning} (if not null) goes to the transcript first. */
    public void start(boolean paused, String warning) {
        thread = Thread.ofPlatform().name("machine").daemon().start(() -> loop(paused, warning)); // daemon: ending the session ends it
    }

    private void loop(boolean paused, String warning) {
        if (warning != null) out.println(warning);
        if (paused) interpreter.show(null);
        else execute("go");
        out.flush();
        while (!exit.isDone()) {
            String line;
            try {
                line = inbox.take();
            } catch (InterruptedException e) {
                return;
            }
            out.println("> " + line);
            execute(line);
            out.flush();
        }
    }

    /** Runs a line while stopped; a Step-executing one runs the Machine on this thread. An unexpected exception becomes a bug stop. */
    private void execute(String line) {
        try {
            switch (interpreter.execute(line)) {
                case Interpreter.Quit(int code) -> exit.complete(code);
                case Interpreter.Stopped(StopReason r) -> {
                    running = false;
                    stop = r;
                    if (r instanceof StopReason.Exited) interpreter.show(r); // an ordinary Stop here; the line Monitor ends instead
                }
                case null -> {}
            }
        } catch (RuntimeException | Error e) {
            running = false;
            bug = true;
            out.println("bug: " + e);
            e.printStackTrace(out);
        }
    }

    /** The Machine's attention between Steps: runs posted lines that need no stop, refuses the rest; false stops the run. */
    private boolean between() {
        for (String line; !breakPending && !exit.isDone() && (line = inbox.poll()) != null; ) {
            out.println("> " + line);
            String w = line.trim().split("\\s+", 2)[0];
            if (!WHILE_RUNNING.contains(w) && Interpreter.COMMANDS.contains(w)) out.println("running: stop first");
            else if (!line.isBlank() && interpreter.execute(line) instanceof Interpreter.Quit(int code)) exit.complete(code); // blank would repeat a step
            out.flush();
        }
        boolean stopNow = breakPending || exit.isDone();
        breakPending = false;
        return !stopNow;
    }

    /** Called by the Interpreter just before a run, after it cleared the attention flag: lines posted meanwhile still get served. */
    private void resumed() {
        breakPending = false; // a Ctrl-] from before this run, dropped as the line Monitor drops it
        running = true;
        bug = false;
        if (!inbox.isEmpty()) machine.breakRequested = true;
    }

    /** Queues a command line; it is echoed in the transcript with its output. */
    public void post(String line) {
        inbox.add(line);
        attention();
    }

    /** Ctrl-]: a running Machine stops before its next Step as a user break; a stopped one ignores it. */
    public void requestBreak() {
        breakPending = true;
        attention();
    }

    private void attention() {
        machine.breakRequested = true;
        LockSupport.unpark(thread);
    }

    /** The transcript text written since the last call. */
    public String output() {
        StringBuffer b = transcript.getBuffer();
        synchronized (b) {
            String s = b.toString();
            b.setLength(0);
            return s;
        }
    }

    /** The exit code for ending the session now: the current Stop's, an Exit's code, 125 after a Machine thread bug, else 0. */
    public int closeCode() {
        if (bug) return Main.FAILURE;
        return !running && stop instanceof StopReason.Exited(int c) ? c : 0;
    }

    /** Ends the session with {@link #closeCode}, stopping a running Machine. */
    public void close() {
        exit.complete(closeCode());
        requestBreak();
    }
}
