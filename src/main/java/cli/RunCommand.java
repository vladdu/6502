package cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import machine.LoadException;
import machine.Machine;
import machine.StopReason;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Run a Machine in Program mode.")
final class RunCommand implements Callable<Integer> {
    @Spec CommandSpec spec;

    @Parameters(index = "0", paramLabel = "IMAGE", description = "Binary to load in Program mode.")
    Path image;

    @Option(names = "--org", paramLabel = "ADDR", converter = Numbers.class, defaultValue = "$0200",
            description = "Load address and start PC (default $0200).")
    int org;

    @Override
    public Integer call() {
        var err = spec.commandLine().getErr();
        var machine = new Machine();
        try {
            machine.loadProgram(Files.readAllBytes(image), org);
        } catch (IOException e) {
            err.println("6502: cannot read " + image + ": " + e);
            return Main.FAILURE;
        } catch (LoadException e) {
            err.println("6502: " + image + ": " + e.getMessage());
            return Main.FAILURE;
        }
        StopReason reason = machine.run(Set.of());
        if (reason instanceof StopReason.Exited(int code)) return code;
        err.println(reason);
        return Main.FAILURE;
    }
}
