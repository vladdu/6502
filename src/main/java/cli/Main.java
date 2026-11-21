package cli;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "6502", subcommands = {RunCommand.class, AsmCommand.class, DisasmCommand.class})
public final class Main implements Runnable {
    /** Simulator failure: load or attach error, or a non-interactive stop other than Exited. */
    static final int FAILURE = 125;

    @Spec CommandSpec spec;

    /** Opens the Terminal the Console runs on, only when `run` needs it. */
    final HostConsole.Opener terminal;

    private Main(HostConsole.Opener terminal) {
        this.terminal = terminal;
    }

    public static void main(String[] args) {
        System.exit(execute(args, HostConsole::system, System.out, System.err));
    }

    /**
     * In-process entry, the Console on a DumbTerminal over {@code in} and {@code out}: exit code is the program's
     * code, 2 for usage errors, 125 for Simulator failures.
     */
    public static int execute(String[] args, InputStream in, PrintStream out, PrintStream err) {
        return execute(args, () -> new DumbTerminal("6502", Terminal.TYPE_DUMB, in, out, UTF_8), out, err);
    }

    private static int execute(String[] args, HostConsole.Opener terminal, PrintStream out, PrintStream err) {
        return new CommandLine(new Main(terminal))
                .setOut(new PrintWriter(out, true, out.charset()))
                .setErr(new PrintWriter(err, true, err.charset()))
                .execute(args);
    }

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }
}
