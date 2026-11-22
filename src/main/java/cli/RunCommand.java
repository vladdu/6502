package cli;

import gui.FrontPanel;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import machine.LoadException;
import machine.Machine;
import machine.StopReason;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Run a Machine in Program mode, or ROM boot with --rom alone.")
final class RunCommand implements Callable<Integer> {
    @Spec CommandSpec spec;
    @ParentCommand Main main;

    @Parameters(index = "0", arity = "0..1", paramLabel = "IMAGE", description = "Binary to load in Program mode.")
    Path image;

    @Option(names = "--rom", paramLabel = "IMG", description = "ROM image (up to 8K) ending at $FFFF.")
    Path rom;

    @Option(names = "--char-rom", paramLabel = "IMG", description = "Character ROM image (up to 2K) starting at $C800.")
    Path charRom;

    @Option(names = "--org", paramLabel = "ADDR", converter = Numbers.class,
            description = "IMAGE's load address, its Entry point moving along (default: its Symbol file's Image origin, else $0200).")
    Integer org;

    @Option(names = "--disk", paramLabel = "FILE", description = "Raw Disk image (Sector N at byte N*256).")
    Path disk;

    @Option(names = "--disk-ro", description = "Attach the Disk read-only.")
    boolean diskRo;

    @Option(names = "--syms", paramLabel = "FILE",
            description = "Symbol file for the Monitor, repeatable; IMAGE's and IMG's .sym next to them load by themselves.")
    List<Path> syms = new ArrayList<>();

    @Option(names = "--speed", paramLabel = "MHZ", converter = Speed.class, description = "Throttle the Machine to MHZ million cycles a second (e.g. 1, 0.985); default unthrottled.")
    long hz;

    @Option(names = "--monitor", description = "Start paused in the Monitor (needs an interactive terminal, unless --gui).")
    boolean monitor;

    @Option(names = "--gui", description = "Run in the Front panel window instead of the terminal (needs a display).")
    boolean gui;

    @Override
    public Integer call() {
        if (diskRo && disk == null) throw new ParameterException(spec.commandLine(), "--disk-ro needs --disk FILE");
        if (image == null && rom == null)
            throw new ParameterException(spec.commandLine(), "Missing IMAGE or --rom IMG");
        var err = spec.commandLine().getErr();
        if (gui && GraphicsEnvironment.isHeadless()) throw new ParameterException(spec.commandLine(), "--gui needs a display");
        Terminal terminal = null; // unused under --gui
        boolean interactive = false;
        if (!gui) {
            try {
                terminal = main.terminal.open();
            } catch (IOException e) {
                err.println("6502: cannot open the terminal: " + e);
                return Main.FAILURE;
            }
            interactive = !(terminal instanceof DumbTerminal); // the system Terminal falls back to dumb off a TTY
            if (monitor && !interactive) {
                close(terminal);
                throw new ParameterException(spec.commandLine(), "--monitor needs an interactive terminal; stdin is not one");
            }
        }
        var consoleIn = new LinkedBlockingQueue<Integer>();
        var paneOut = new ByteArrayOutputStream(); // the Console pane's, under --gui
        var consoleOut = new Monitor.ConsoleOut(gui ? paneOut : terminal.output());
        var machine = new Machine(consoleIn, consoleOut);
        machine.hz = hz;
        var launch = new Launch(machine, rom, charRom, image, org, disk, diskRo, syms);
        Launch.Started started;
        try {
            started = launch.start();
        } catch (LoadException e) {
            err.println("6502: " + e.getMessage());
            close(terminal);
            return Main.FAILURE;
        }
        var warnings = new ArrayList<>(started.warnings());
        if (disk != null && !diskRo && machine.disk.readOnly()) warnings.add("warning: " + disk + ": not writable, Disk mounted read-only");
        if (gui)
            return FrontPanel.run(machine, consoleIn, paneOut, (out, resume) -> new Interpreter(machine, out, consoleOut, started, launch, resume, () -> {}), monitor, warnings);
        for (String warning : warnings) err.println("6502: " + warning);
        StopReason reason = null; // null: paused at the start
        Integer code = null;
        try (var host = new HostConsole(terminal, consoleIn, interactive ? () -> machine.breakRequested = true : null)) {
            if (!monitor) reason = machine.run(Set.of());
            if (reason instanceof StopReason.Exited(int c)) code = c;
            else if (interactive)
                code = new Monitor(machine, terminal, consoleOut, started, launch, host::resume, host::pause).session(reason);
        }
        close(terminal);
        if (code != null) return code;
        err.println(reason);
        return Main.FAILURE;
    }

    private void close(Terminal terminal) {
        if (terminal == null) return;
        try {
            terminal.close();
        } catch (IOException e) {
            spec.commandLine().getErr().println("6502: cannot close the terminal: " + e);
        }
    }
}
