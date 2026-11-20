package cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import disasm.SymbolFile;
import disasm.SymbolFileException;
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

    /** The file a launch error is about. */
    private Path current;

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
        var symbols = new ArrayList<SymbolFile.Symbol>();
        var files = new ArrayList<Path>();
        current = disk;
        try {
            if (disk != null) {
                machine.disk.attach(disk, diskRo);
                if (!diskRo && machine.disk.readOnly())
                    err.println("6502: warning: " + disk + ": not writable, Disk mounted read-only");
            }
            start(machine);
            for (Path p : new Path[] {rom, image}) if (p != null && Files.exists(sibling(p))) files.add(sibling(p));
            files.addAll(syms);
            for (Path p : files) {
                current = p;
                symbols.addAll(SymbolFile.parse(p.toString(), Files.readString(p)));
            }
        } catch (SymbolFileException e) {
            err.println("6502: " + e.getMessage());
            close(terminal);
            return Main.FAILURE;
        } catch (IOException e) {
            err.println("6502: cannot read " + current + ": " + e);
            close(terminal);
            return Main.FAILURE;
        } catch (LoadException e) {
            err.println("6502: " + current + ": " + e.getMessage());
            close(terminal);
            return Main.FAILURE;
        }
        StopReason reason = null; // null: paused at the start
        Integer code = null;
        try (var host = new HostConsole(terminal, consoleIn, interactive ? () -> machine.breakRequested = true : null)) {
            if (!monitor) reason = machine.run(Set.of());
            if (reason instanceof StopReason.Exited(int c)) code = c;
            else if (interactive) {
                var images = new ArrayList<String>();
                if (rom != null) images.add(rom + " (ROM)");
                if (image != null) images.add(String.format("%s at $%04X", image, org));
                var launch = new Monitor.Launch(images, files.stream().map(Path::toString).toList(), () -> {
                    try {
                        start(machine);
                    } catch (IOException e) {
                        throw new LoadException("cannot read " + current + ": " + e);
                    } catch (LoadException e) {
                        throw new LoadException(current + ": " + e.getMessage());
                    }
                });
                code = new Monitor(machine, terminal, consoleOut, symbols, launch, host::resume, host::pause).session(reason);
            }
        }
        close(terminal);
        if (code != null) return code;
        err.println(reason);
        return Main.FAILURE;
    }

    /** The launch, again on a Monitor restart: ROM attached, then the program loaded or the ROM booted. */
    private void start(Machine machine) throws IOException, LoadException {
        current = rom;
        if (rom != null) machine.attachRom(Files.readAllBytes(rom));
        current = image;
        if (image != null) machine.loadProgram(Files.readAllBytes(image), org);
        else {
            current = rom;
            machine.bootRom();
        }
    }

    /** {@code prog.bin} -> {@code prog.sym}, next to it. */
    static Path sibling(Path image) {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".sym");
    }

    private void close(Terminal terminal) {
        try {
            terminal.close();
        } catch (IOException e) {
            spec.commandLine().getErr().println("6502: cannot close the terminal: " + e);
        }
    }
}
