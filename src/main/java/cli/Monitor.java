package cli;

import cpu.Cpu;
import cpu.IllegalOpcode;
import cpu.StrayInterrupt;
import disasm.Disassembler;
import disasm.SymbolFile.Symbol;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;
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

/** The Simulator's interactive debugger, on the Machine thread and the Console's Terminal. */
final class Monitor {
    /** The Console's output, remembering the last byte so the Monitor can start on a fresh line. */
    static final class ConsoleOut extends FilterOutputStream {
        private int last = '\n';

        ConsoleOut(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            last = b & 0xFF;
            out.write(b);
        }
    }

    private static final List<String> COMMANDS = List.of("go", "continue", "step", "s", "b", "r", "quit", "q", "help");
    private static final String HELP = """
            go [ADDR] | continue    run (from ADDR) to the next stop
            step [N] | s [N]        N Steps (default 1); an empty line repeats it
            b ADDR | b del ADDR     add or remove a breakpoint; bare b lists them
            r [NAME=VALUE ...]      show registers; set pc a x y s p, or a flag n v d i z c
            quit [CODE] | q [CODE]  exit the Simulator with CODE (default 0); Ctrl-D quits too
            help                    this list
            Numbers are hex ($ optional), #10 is decimal; symbols as sym, sym+N or sym-N.""";

    private final Machine machine;
    private final ConsoleOut console;
    private final List<Symbol> symbols;
    /** Symbol values by name; a later symbol wins a clash. */
    private final Map<String, Integer> values = new HashMap<>();
    private final Set<Integer> breakpoints = new TreeSet<>();
    private final Runnable resume, pause;
    /** The last command if an empty line repeats it ({@code step}), else null. */
    private String[] repeat;
    private final LineReader reader;
    private final PrintWriter out;

    Monitor(Machine machine, Terminal terminal, ConsoleOut console, List<Symbol> symbols, Runnable resume, Runnable pause) {
        this.machine = machine;
        this.console = console;
        this.symbols = symbols;
        for (Symbol s : symbols) values.put(s.name(), s.value());
        this.resume = resume;
        this.pause = pause;
        this.reader = LineReaderBuilder.builder().terminal(terminal)
                .completer(new ArgumentCompleter(new StringsCompleter(COMMANDS), NullCompleter.INSTANCE)).build();
        reader.getWidgets().put("ignore", () -> true);
        KeyMap<Binding> keys = reader.getKeyMaps().get(LineReader.MAIN);
        keys.bind(new Reference("ignore"), KeyMap.ctrl(']'));
        keys.bind(new Reference(LineReader.INTERRUPT), KeyMap.ctrl('C')); // also on a dumb terminal, whose keymap lacks it
        this.out = terminal.writer();
    }

    /** A command the user typed wrong: its message is printed and the session goes on. */
    private static final class Mistake extends Exception {
        Mistake(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Shows the stop display for {@code reason} (null: paused before the first Step), then reads commands until
     * one exits; returns the exit code.
     */
    int session(StopReason reason) {
        pause.run();
        show(reason == null ? "Paused" : reason(reason));
        while (true) {
            String line;
            try {
                line = reader.readLine("> ");
            } catch (UserInterruptException e) {
                continue; // Ctrl-C cancels the line
            } catch (EndOfFileException e) {
                return 0;
            }
            String[] w = line.isBlank() && repeat != null ? repeat : line.trim().split("\\s+");
            repeat = null;
            try {
                Integer exit = switch (w[0]) {
                    case "" -> null;
                    case "go", "continue" -> go(w);
                    case "step", "s" -> step(repeat = w);
                    case "b" -> breakpoint(w);
                    case "r" -> registers(w);
                    case "quit", "q" -> quit(w);
                    case "help" -> {
                        out.println(HELP);
                        out.flush();
                        yield null;
                    }
                    default -> throw new Mistake("unknown command '" + w[0] + "' (help lists them)");
                };
                if (exit != null) return exit;
            } catch (Mistake e) {
                out.println(e.getMessage());
                out.flush();
            }
        }
    }

    /** {@code quit [code]}: the exit code. */
    private Integer quit(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: quit [CODE]");
        int code = w.length == 2 ? value(w[1]) : 0;
        if (code >>> 8 != 0) throw new Mistake(String.format("%s: exit code $%X is not a byte", w[1], code));
        return code;
    }

    /** {@code go [ADDR]}: runs (from ADDR if given) to the next stop; the exit code if the program exited. */
    private Integer go(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: go [ADDR]");
        if (w.length == 2) machine.cpu.pc = address(w[1]);
        resume.run();
        StopReason r = machine.run(breakpoints);
        pause.run();
        if (r instanceof StopReason.Exited(int code)) return code;
        show(reason(r));
        return null;
    }

    /** {@code step [n]}: n Steps (default 1), stopping early on an Illegal, StrayInterrupt or user break. */
    private Integer step(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: step [N]");
        int n = w.length == 2 ? value(w[1]) : 1;
        String reason = "Step";
        resume.run();
        try {
            for (int i = 0; i < n; i++) {
                if (machine.breakRequested) {
                    machine.breakRequested = false;
                    reason = reason(new StopReason.UserBreak());
                    break;
                }
                machine.step();
                if (machine.pendingExit() != null) return machine.pendingExit();
                String entry = machine.cpu.entry;
                reason = entry == null ? "Step" : String.format("%s entry -> $%04X", entry, machine.cpu.pc);
            }
        } catch (IllegalOpcode e) {
            reason = reason(new StopReason.Illegal(e.pc, e.opcode));
        } catch (StrayInterrupt e) {
            reason = reason(new StopReason.StrayInterrupt(e.vector));
        } finally {
            pause.run();
        }
        show(reason);
        return null;
    }

    /** {@code b ADDR} adds, {@code b del ADDR} removes, bare {@code b} lists. */
    private Integer breakpoint(String[] w) throws Mistake {
        if (w.length == 1) {
            if (breakpoints.isEmpty()) out.println("no breakpoints");
            for (int a : breakpoints) out.println(String.format("$%04X", a) + label(a));
        } else if (w.length == 2) {
            breakpoints.add(address(w[1]));
        } else if (w.length == 3 && w[1].equals("del")) {
            int a = address(w[2]);
            if (!breakpoints.remove(a)) out.println(String.format("no breakpoint at $%04X", a));
        } else throw new Mistake("usage: b [ADDR] | b del ADDR");
        out.flush();
        return null;
    }

    /** {@code r} shows the registers; {@code r pc=0213 a=ff p=24 c=1 ...} sets registers and single flags first. */
    private Integer registers(String[] w) throws Mistake {
        Cpu c = machine.cpu;
        int[] values = new int[w.length];
        for (int i = 1; i < w.length; i++) {
            int eq = w[i].indexOf('=');
            String name = eq < 0 ? w[i] : w[i].substring(0, eq);
            int max = switch (name) {
                case "pc" -> 0xFFFF;
                case "a", "x", "y", "s", "p" -> 0xFF;
                case "n", "v", "b", "d", "i", "z", "c" -> 1;
                default -> throw new Mistake("unknown register or flag '" + name + "'");
            };
            if (eq < 0) throw new Mistake(w[i] + ": expected " + name + "=VALUE");
            values[i] = value(w[i].substring(eq + 1));
            if (Integer.compareUnsigned(values[i], max) > 0) throw new Mistake(String.format("%s: value $%X is out of range", w[i], values[i]));
        }
        for (int i = 1; i < w.length; i++) {
            String name = w[i].substring(0, w[i].indexOf('='));
            int v = values[i];
            switch (name) {
                case "pc" -> c.pc = v;
                case "a" -> c.a = v;
                case "x" -> c.x = v;
                case "y" -> c.y = v;
                case "s" -> c.s = v;
                case "p" -> c.p = v & ~Cpu.B | Cpu.U;
                case "b" -> {}
                default -> {
                    int bit = 0x80 >> "nv-bdizc".indexOf(name);
                    c.p = v == 1 ? c.p | bit : c.p & ~bit;
                }
            }
        }
        out.println(registers());
        out.flush();
        return null;
    }

    /** A {@link #value} that fits in 16 bits. */
    private int address(String s) throws Mistake {
        int v = value(s);
        if (v >>> 16 != 0) throw new Mistake(String.format("%s: $%X is not an address", s, v));
        return v;
    }

    /** Bare numbers are hex ({@code $} optional), {@code #} decimal; a symbol, optionally {@code sym+n} / {@code sym-n}. */
    private int value(String s) throws Mistake {
        Integer v = values.get(s);
        if (v != null) return v;
        if (s.matches("\\$?[0-9A-Fa-f]{1,8}")) return (int) Long.parseLong(s.replace("$", ""), 16);
        if (s.matches("#[0-9]{1,9}")) return Integer.parseInt(s.substring(1));
        int at = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
        if (at > 0 && values.containsKey(s.substring(0, at))) {
            int n = value(s.substring(at + 1));
            return values.get(s.substring(0, at)) + (s.charAt(at) == '+' ? n : -n);
        }
        throw new Mistake("'" + s + "' is not a number or symbol");
    }

    private String reason(StopReason r) {
        String s = r.toString();
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        if (r instanceof StopReason.Breakpoint(int pc)) s += label(pc);
        return s;
    }

    /** {@code " (name)"} for the first label symbol at {@code addr}, else empty. */
    private String label(int addr) {
        for (Symbol s : symbols) if (s.label() && s.value() == addr) return " (" + s.name() + ")";
        return "";
    }

    /** The stop display: reason, registers, current instruction. */
    private void show(String reason) {
        if (console.last != '\n') {
            out.println();
            console.last = '\n';
        }
        out.println(reason);
        out.println(registers());
        out.println(instruction(machine.cpu.pc));
        out.flush();
    }

    private String registers() {
        Cpu c = machine.cpu;
        var flags = new StringBuilder();
        String names = "nv-bdizc";
        for (int i = 0; i < 8; i++) {
            char f = names.charAt(i);
            flags.append((c.p & 0x80 >> i) != 0 && f != '-' ? Character.toUpperCase(f) : f);
        }
        return String.format("PC=%04X A=%02X X=%02X Y=%02X S=%02X P=%s  cyc=%d", c.pc, c.a, c.x, c.y, c.s, flags, machine.cycles);
    }

    /** The instruction at {@code addr} in the Assembler listing layout, operands named by label symbols. */
    private String instruction(int addr) {
        IntUnaryOperator peek = machine.bus::peek;
        int len = Disassembler.decode(peek, addr).bytes().length;
        for (var line : Disassembler.sweep(peek, addr, addr + len, symbols))
            if (line instanceof Disassembler.Code c) {
                var hex = new StringBuilder();
                for (int b : c.bytes()) hex.append(hex.isEmpty() ? "" : " ").append(String.format("%02X", b));
                return String.format("%04X  %-8s  %s", c.addr(), hex, c.text()).stripTrailing();
            }
        throw new AssertionError("sweep of one instruction has no code");
    }
}
