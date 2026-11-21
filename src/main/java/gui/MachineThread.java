package gui;

import cli.Interpreter;
import cli.Main;
import cpu.Cpu;
import disasm.SymbolTable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import machine.Machine;
import machine.StopReason;

/**
 * The Machine thread: the only thread that touches the Machine and the Interpreter. Other threads post command lines and
 * breaks; stopped, it runs posted lines in order, and running, it runs them between Steps through the Machine's attention flag.
 * It publishes a {@link Snapshot} after every command, at every Stop, just before every run and, running, on {@link #requestSnapshot}.
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
    /** Stops so far, a bug stop included: the Front panel moves focus on each. */
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    /** The refresh tick asked a running Machine for a snapshot. */
    private volatile boolean snapshotRequested;
    /** Machine thread only: the last id published, the Symbol table copy and what it was taken from, the baseline. */
    private long published;
    private SymbolTable symbolsFrom, symbols;
    private int symbolFiles;
    private long baseline;
    private byte[] baselineMemory;
    /** Machine thread only: how many of the lines still queued were posted before the current run began. */
    private int queuedBefore;

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
        publish();
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
            publish();
            out.flush();
        }
    }

    /** Runs a line while stopped; a Step-executing one runs the Machine on this thread. An unexpected exception becomes a bug stop. */
    private void execute(String line) {
        try {
            switch (interpreter.execute(line)) {
                case Interpreter.Quit(int code) -> exit.complete(code);
                case Interpreter.Stopped(StopReason r) -> {
                    stop = r; // closeCode trusts stop once running is false, so stop goes first
                    running = false;
                    stops.incrementAndGet();
                    if (r instanceof StopReason.Exited) interpreter.show(r); // an ordinary Stop here; the line Monitor ends instead
                }
                case null -> {}
            }
        } catch (RuntimeException | Error e) {
            bug = true; // likewise
            running = false;
            stops.incrementAndGet();
            out.println("bug: " + e);
            e.printStackTrace(out);
        }
    }

    /** The Machine's attention between Steps: runs posted lines that need no stop, refuses the rest, publishes; false stops the run. */
    private boolean between() {
        boolean served = snapshotRequested;
        snapshotRequested = false;
        for (String line; !breakPending && !exit.isDone() && (line = inbox.peek()) != null; ) {
            String w = line.trim().split("\\s+", 2)[0];
            boolean runControl = !WHILE_RUNNING.contains(w) && Interpreter.COMMANDS.contains(w);
            if (queuedBefore > 0 && (runControl || line.isBlank())) break; // posted before this run: it and what follows wait for the Stop
            inbox.remove();
            queuedBefore--;
            out.println("> " + line);
            if (runControl) out.println("running: stop first");
            else if (!line.isBlank() && interpreter.execute(line) instanceof Interpreter.Quit(int code)) exit.complete(code); // blank would repeat a step
            out.flush();
            served = true;
        }
        boolean stopNow = breakPending || exit.isDone();
        breakPending = false;
        if (served && !stopNow) publish(); // a stopping run publishes at its Stop
        return !stopNow;
    }

    /** Called by the Interpreter just before a run, after it cleared the attention flag: lines posted meanwhile still get served. */
    private void resumed() {
        breakPending = false; // a Ctrl-] from before this run, dropped as the line Monitor drops it
        queuedBefore = inbox.size(); // before the running snapshot, so a line posted once it shows is a line posted while running
        running = true;
        bug = false;
        Snapshot before = publish();
        baseline = before.id();
        baselineMemory = before.memory();
        if (queuedBefore > 0) machine.breakRequested = true;
    }

    /** Builds a snapshot of the Machine as it is now and publishes it; on the Machine thread, between Steps. */
    private Snapshot publish() {
        SymbolTable t = interpreter.symbols();
        // the table only grows a file at a time, and restart replaces it
        if (t != symbolsFrom || t.files().size() != symbolFiles) {
            symbolsFrom = t;
            symbolFiles = t.files().size();
            symbols = t.copy();
        }
        var memory = new byte[0x10000];
        for (int a = 0; a < memory.length; a++) memory[a] = (byte) machine.bus.peek(a);
        long id = ++published;
        if (baselineMemory == null) { // before any run the first snapshot is the baseline, so m = edits show as changes
            baseline = id;
            baselineMemory = memory;
        }
        Cpu c = machine.cpu;
        var s = new Snapshot(id, c.pc, c.a, c.x, c.y, c.s, c.p, machine.pins(), machine.cycles, machine.steps,
                running, stop, bug ? "Machine thread bug" : interpreter.lastStop(),
                machine.states(), interpreter.breakpoints(), interpreter.watchpoints(), memory, symbols,
                baseline, baselineMemory, machine.areas(), machine.previousPc);
        snapshot.set(s);
        return s;
    }

    /** The latest snapshot; null until the thread has started. */
    public Snapshot snapshot() {
        return snapshot.get();
    }

    /** The refresh tick: a running Machine publishes a snapshot at its next Step boundary; a stopped one already did. */
    public void requestSnapshot() {
        if (!running) return;
        snapshotRequested = true;
        attention();
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

    /** How many Stops there have been. */
    public int stops() {
        return stops.get();
    }

    /** The exit code for ending the session now: the current Stop's, an Exit's code, 125 after a Machine thread bug, else 0. */
    public int closeCode() {
        if (running) return 0; // read first: the Machine thread sets bug and stop before running goes false
        if (bug) return Main.FAILURE;
        return stop instanceof StopReason.Exited(int c) ? c : 0;
    }

    /** Ends the session with {@link #closeCode}, stopping a running Machine. */
    public void close() {
        exit.complete(closeCode());
        requestBreak();
    }
}
