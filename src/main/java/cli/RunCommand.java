package cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import machine.LoadException;
import machine.Machine;
import machine.StopReason;
import org.jline.terminal.Terminal;
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
        var consoleIn = new LinkedBlockingQueue<Integer>();
        var machine = new Machine(consoleIn, terminal.output());
        Path current = disk; // the file an error is about
        try {
            if (disk != null) {
                machine.disk.attach(disk, diskRo);
                if (!diskRo && machine.disk.readOnly())
                    err.println("6502: warning: " + disk + ": not writable, Disk mounted read-only");
            }
            current = rom;
            if (rom != null) machine.attachRom(Files.readAllBytes(rom));
            current = image;
            if (image != null) machine.loadProgram(Files.readAllBytes(image), org);
            else {
                current = rom;
                machine.bootRom();
            }
        } catch (IOException e) {
            err.println("6502: cannot read " + current + ": " + e);
            return Main.FAILURE;
        } catch (LoadException e) {
            err.println("6502: " + current + ": " + e.getMessage());
            return Main.FAILURE;
        }
        StopReason reason;
        try (var host = new HostConsole(terminal, consoleIn, () -> machine.breakRequested = true)) {
            reason = machine.run(Set.of());
        }
        try {
            terminal.close();
        } catch (IOException e) {
            err.println("6502: cannot close the terminal: " + e);
        }
        if (reason instanceof StopReason.Exited(int code)) return code;
        err.println(reason);
        return Main.FAILURE;
    }
}
