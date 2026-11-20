package cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "6502", subcommands = {RunCommand.class, AsmCommand.class})
public final class Main implements Runnable {
    /** Simulator failure: load or attach error, or a non-interactive stop other than Exited. */
    static final int FAILURE = 125;

    @Spec CommandSpec spec;

    public static void main(String[] args) {
        System.exit(execute(args, System.in, System.out, System.err));
    }

    /** In-process entry: exit code is the program's code, 2 for usage errors, 125 for Simulator failures. */
    public static int execute(String[] args, InputStream in, PrintStream out, PrintStream err) {
        return new CommandLine(new Main())
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }
}
