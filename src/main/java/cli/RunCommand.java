package cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import disasm.SymbolFile;
import machine.LoadException;
import machine.Machine;
import machine.StopReason;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Run a Machine in Program mode, or ROM boot with --rom alone.")
final class RunCommand implements Callable<Integer> {
    @Spec CommandSpec spec;
    @ParentCommand Main main;

    @Parameters(index = "0", arity = "0..1", paramLabel = "IMAGE", description = "Binary to load in Program mode.")
    Path image;

    @Option(names = "--rom", paramLabel = "IMG", description = "ROM image (up to 8K) ending at $FFFF.")
    Path rom;

    @Option(names = "--org", paramLabel = "ADDR", converter = Numbers.class, defaultValue = "$0200",
            description = "Load address and start PC (default $0200).")
    int org;

    @Option(names = "--disk", paramLabel = "FILE", description = "Raw Disk image (Sector N at byte N*256).")
    Path disk;

    @Option(names = "--disk-ro", description = "Attach the Disk read-only.")
    boolean diskRo;

    @Option(names = "--syms", paramLabel = "FILE",
            description = "Symbol file for the Monitor, repeatable; IMAGE's and IMG's .sym next to them load by themselves.")
    List<Path> syms = new ArrayList<>();

    @Option(names = "--speed", paramLabel = "MHZ", converter = Speed.class, description = "Throttle the Machine to MHZ million cycles a second (e.g. 1, 0.985); default unthrottled.")
    long hz;

    @Option(names = "--monitor", description = "Start paused in the Monitor (needs an interactive terminal).")
    boolean monitor;

    @Override
    public Integer call() {
        if (diskRo && disk == null) throw new ParameterException(spec.commandLine(), "--disk-ro needs --disk FILE");
        if (image == null && rom == null)
            throw new ParameterException(spec.commandLine(), "Missing IMAGE or --rom IMG");
        var err = spec.commandLine().getErr();
        Terminal terminal;
        try {
            terminal = main.terminal.open();
        } catch (IOException e) {
            err.println("6502: cannot open the terminal: " + e);
            return Main.FAILURE;
        }
        boolean interactive = !(terminal instanceof DumbTerminal); // the system Terminal falls back to dumb off a TTY
        if (monitor && !interactive) {
            close(terminal);
            throw new ParameterException(spec.commandLine(), "--monitor needs an interactive terminal; stdin is not one");
        }
        var consoleIn = new LinkedBlockingQueue<Integer>();
        var consoleOut = new Monitor.ConsoleOut(terminal.output());
        var machine = new Machine(consoleIn, consoleOut);
        machine.hz = hz;
        var launch = new Launch(machine, rom, image, org, disk, diskRo, syms);
        List<SymbolFile.Symbol> symbols;
        try {
            symbols = launch.start();
        } catch (LoadException e) {
            err.println("6502: " + e.getMessage());
            close(terminal);
            return Main.FAILURE;
        }
        if (disk != null && !diskRo && machine.disk.readOnly())
            err.println("6502: warning: " + disk + ": not writable, Disk mounted read-only");
        StopReason reason = null; // null: paused at the start
        Integer code = null;
        try (var host = new HostConsole(terminal, consoleIn, interactive ? () -> machine.breakRequested = true : null)) {
            if (!monitor) reason = machine.run(Set.of());
            if (reason instanceof StopReason.Exited(int c)) code = c;
            else if (interactive)
                code = new Monitor(machine, terminal, consoleOut, symbols, launch, host::resume, host::pause).session(reason);
        }
        close(terminal);
        if (code != null) return code;
        err.println(reason);
        return Main.FAILURE;
    }

    private void close(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            spec.commandLine().getErr().println("6502: cannot close the terminal: " + e);
        }
    }
}
