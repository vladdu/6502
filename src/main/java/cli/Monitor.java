package cli;

import disasm.SymbolTable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import machine.Machine;
import machine.StopReason;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;

/** The line Monitor: the Simulator's interactive debugger on the Machine thread and the Console's Terminal, reading lines for the Interpreter. */
final class Monitor {
    /** The Console's output, remembering the last byte so the Monitor can start on a fresh line. */
    static final class ConsoleOut extends FilterOutputStream {
        int last = '\n';

        ConsoleOut(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            last = b & 0xFF;
            out.write(b);
        }
    }

    private final Interpreter interpreter;
    private final Runnable pause;
    private final LineReader reader;

    Monitor(Machine machine, Terminal terminal, ConsoleOut console, SymbolTable symbols, Launch launch, Runnable resume, Runnable pause) {
        this.interpreter = new Interpreter(machine, terminal.writer(), console, symbols, launch, resume, pause);
        this.pause = pause;
        this.reader = LineReaderBuilder.builder().terminal(terminal)
                .completer(new ArgumentCompleter(new StringsCompleter(Interpreter.COMMANDS), NullCompleter.INSTANCE)).build();
        reader.getWidgets().put("ignore", () -> true);
        KeyMap<Binding> keys = reader.getKeyMaps().get(LineReader.MAIN);
        keys.bind(new Reference("ignore"), KeyMap.ctrl(']'));
        keys.bind(new Reference(LineReader.INTERRUPT), KeyMap.ctrl('C')); // also on a dumb terminal, whose keymap lacks it
    }

    /**
     * Shows the stop display for {@code reason} (null: paused before the first Step), then reads commands until
     * one exits; returns the exit code.
     */
    int session(StopReason reason) {
        pause.run();
        interpreter.show(reason);
        while (true) {
            String line;
            try {
                line = reader.readLine("> ");
            } catch (UserInterruptException _) {
                continue; // Ctrl-C cancels the line
            } catch (EndOfFileException _) {
                return 0;
            }
            switch (interpreter.execute(line)) {
                case Interpreter.Quit(int code) -> {
                    return code;
                }
                case Interpreter.Stopped(StopReason.Exited(int code)) -> {
                    return code;
                }
                case null, default -> {}
            }
        }
    }
}
