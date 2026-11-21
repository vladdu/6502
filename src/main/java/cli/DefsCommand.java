package cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(name = "defs", description = "Print machine.inc, the Machine's memory map and Device registers as Assembler constants.")
final class DefsCommand implements Runnable {
    @Spec CommandSpec spec;

    /** The text of machine.inc, shipped in the jar. */
    static String text() {
        try (InputStream in = Objects.requireNonNull(DefsCommand.class.getResourceAsStream("/machine.inc"), "machine.inc is missing from the jar")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void run() {
        spec.commandLine().getOut().print(text());
        spec.commandLine().getOut().flush();
    }
}
