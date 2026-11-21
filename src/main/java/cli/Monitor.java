package cli;

import cpu.Cpu;
import cpu.IllegalOpcode;
import cpu.StrayInterrupt;
import disasm.Disassembler;
import disasm.SymbolFile;
import disasm.SymbolFile.Symbol;
import disasm.SymbolFileException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;
import machine.Disk;
import machine.LoadException;
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
import picocli.CommandLine.TypeConversionException;

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

    private static final List<String> COMMANDS = List.of("go", "continue", "step", "s", "next", "n", "b", "r", "m", "d",
            "load", "syms", "reset", "restart", "info", "speed", "quit", "q", "help");
    private static final String HELP = """
            go [ADDR] | continue    run (from ADDR) to the next stop
            step [N] | s [N]        N Steps (default 1); an empty line repeats it
            next | n                step, but over a JSR to its return; an empty line repeats it
            b ADDR | b del ADDR     add or remove a breakpoint; bare b lists them
            r [NAME=VALUE ...]      show registers; set pc a x y s p, or a flag n v d i z c
            m [ADDR [LEN]]          dump LEN bytes (default 128); bare m continues
            m ADDR [LEN] = BB ...   write the bytes through the Bus, repeated over LEN bytes
            d [ADDR [N]]            disassemble N instructions (default 16); bare d continues
            load FILE [ADDR]        copy FILE to ADDR (default $0200), and its .sym if there
            syms FILE               merge a symbol file; later names win
            reset                   hardware Reset: Devices reset, PC from $FFFC, memory kept
            restart                 start again as launched: RAM zeroed, images reloaded
            info                    Disk, Timer, speed, loaded images and symbol files
            speed [MHZ | off]       show or set the throttle (e.g. 1, 0.985), from the next run
            quit [CODE] | q [CODE]  exit the Simulator with CODE (default 0); Ctrl-D quits too
            help                    this list
            Numbers are hex ($ optional), #10 is decimal; symbols as sym, sym+N or sym-N.""";

    private final Machine machine;
    private final ConsoleOut console;
    private final List<Symbol> symbols;
    private final Launch launch;
    /** Launched images, then those of {@code load}; a restart drops the latter. */
    private final List<String> images;
    private final List<String> symbolFiles;
    /** Symbol values by name; a later symbol wins a clash. */
    private final Map<String, Integer> values = new HashMap<>();
    private final Set<Integer> breakpoints = new TreeSet<>();
    private final Runnable resume, pause;
    /** Where a bare {@code m} continues; null before the first dump. */
    private Integer dumpNext;
    /** Where a bare {@code d} continues; null before the first listing. */
    private Integer listNext;
    /** The last command if an empty line repeats it ({@code step}), else null. */
    private String[] repeat;
    private final LineReader reader;
    private final PrintWriter out;

    Monitor(Machine machine, Terminal terminal, ConsoleOut console, List<Symbol> symbols, Launch launch, Runnable resume, Runnable pause) {
        this.machine = machine;
        this.console = console;
        this.symbols = new ArrayList<>(symbols);
        this.launch = launch;
        this.images = new ArrayList<>(launch.images());
        this.symbolFiles = new ArrayList<>(launch.symbolFiles());
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
                    case "next", "n" -> next(repeat = w);
                    case "b" -> breakpoint(w);
                    case "r" -> registers(w);
                    case "m" -> memory(w);
                    case "d" -> disassemble(w);
                    case "reset" -> {
                        if (w.length > 1) throw new Mistake("usage: reset");
                        machine.reset();
                        show("Reset");
                        yield null;
                    }
                    case "restart" -> restart(w);
                    case "load" -> load(w);
                    case "info" -> info(w);
                    case "speed" -> speed(w);
                    case "syms" -> {
                        if (w.length != 2) throw new Mistake("usage: syms FILE");
                        symbols(Path.of(w[1]));
                        yield null;
                    }
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

    /** {@code restart}: the launch again on a cleared Machine (RAM zeroed, images reloaded). */
    private Integer restart(String[] w) throws Mistake {
        if (w.length > 1) throw new Mistake("usage: restart");
        images.subList(launch.images().size(), images.size()).clear();
        try {
            launch.restart();
        } catch (LoadException e) {
            throw new Mistake(e.getMessage());
        }
        show("Restart");
        return null;
    }

    /** {@code load FILE [ADDR]}: copies the bytes (default $0200), registers unchanged; a sibling .sym loads too. */
    private Integer load(String[] w) throws Mistake {
        if (w.length < 2 || w.length > 3) throw new Mistake("usage: load FILE [ADDR]");
        int a = w.length == 3 ? address(w[2]) : 0x0200;
        Path file = Path.of(w[1]);
        try {
            machine.load(Files.readAllBytes(file), a);
        } catch (IOException e) {
            throw new Mistake("cannot read " + file + ": " + e);
        } catch (LoadException e) {
            throw new Mistake(file + ": " + e.getMessage());
        }
        images.add(String.format("%s at $%04X", file, a));
        Path sym = Launch.sibling(file);
        if (Files.exists(sym)) symbols(sym);
        return null;
    }

    /** Merges a symbol file; a later symbol wins a name clash. A malformed file is refused whole. */
    private void symbols(Path file) throws Mistake {
        List<Symbol> added;
        try {
            added = SymbolFile.parse(file.toString(), Files.readString(file));
        } catch (IOException e) {
            throw new Mistake("cannot read " + file + ": " + e);
        } catch (SymbolFileException e) {
            throw new Mistake(e.getMessage());
        }
        var names = new HashSet<String>();
        for (Symbol s : added) names.add(s.name());
        symbols.removeIf(s -> names.contains(s.name()));
        symbols.addAll(added);
        for (Symbol s : added) values.put(s.name(), s.value());
        symbolFiles.add(file.toString());
    }

    /** {@code info}: the Disk and Timer as their registers show them (via peek), then images and symbol files. */
    private Integer info(String[] w) throws Mistake {
        if (w.length > 1) throw new Mistake("usage: info");
        IntUnaryOperator disk = off -> machine.bus.peek(Disk.BASE + off), timer = off -> machine.bus.peek(Machine.TIMER + off);
        int status = disk.applyAsInt(0x01), control = timer.applyAsInt(0x02);
        out.println(machine.disk.path() == null ? "Disk: no media" : String.format("Disk: %s, %d bytes, %s, %s",
                machine.disk.path(), ((disk.applyAsInt(0x08) | disk.applyAsInt(0x09) << 8) + 1) * 256,
                (status & 0x08) != 0 ? "read-only" : "read-write", (status & 0x40) != 0 ? "busy" : "idle"));
        out.println(String.format("Timer: %s, %s, IRQ %s, flag %s, reload $%04X",
                (control & 0x01) != 0 ? "running" : "stopped", (control & 0x02) != 0 ? "periodic" : "one-shot",
                (control & 0x80) != 0 ? "on" : "off", (timer.applyAsInt(0x01) & 0x80) != 0 ? "set" : "clear",
                timer.applyAsInt(0x04) | timer.applyAsInt(0x05) << 8));
        out.println(speed());
        out.println("Images: " + (images.isEmpty() ? "none" : String.join(", ", images)));
        out.println("Symbol files: " + (symbolFiles.isEmpty() ? "none" : String.join(", ", symbolFiles)));
        out.flush();
        return null;
    }

    /** {@code speed} shows, {@code speed MHZ} sets, {@code speed off} turns off the throttle; the next run paces afresh anyway. */
    private Integer speed(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: speed [MHZ | off]");
        if (w.length == 1) out.println(speed());
        else if (w[1].equals("off")) machine.hz = 0;
        else try {
            machine.hz = new Speed().convert(w[1]);
        } catch (TypeConversionException e) {
            throw new Mistake(e.getMessage());
        }
        out.flush();
        return null;
    }

    private String speed() {
        return machine.hz == 0 ? "Speed: unthrottled"
                : "Speed: " + BigDecimal.valueOf(machine.hz, 6).stripTrailingZeros().toPlainString() + " MHz";
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

    /**
     * {@code next}: on a JSR, runs to its return (PC+3 with S back at least where it was, so a recursive call
     * returning there doesn't count); otherwise one {@code step}. Any other stop ends it.
     */
    private Integer next(String[] w) throws Mistake {
        if (w.length > 1) throw new Mistake("usage: next");
        Cpu c = machine.cpu;
        if (machine.bus.peek(c.pc) != 0x20) return step(new String[] {"step"});
        int ret = c.pc + 3 & 0xFFFF, s = c.s;
        var stops = new TreeSet<>(breakpoints);
        stops.add(ret);
        resume.run();
        StopReason r;
        do r = machine.run(stops);
        while (r instanceof StopReason.Breakpoint(int pc) && pc == ret && !breakpoints.contains(ret) && c.s < s);
        pause.run();
        if (r instanceof StopReason.Exited(int code)) return code;
        show(r instanceof StopReason.Breakpoint(int pc) && pc == ret && !breakpoints.contains(ret) ? "Step" : reason(r));
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

    /**
     * {@code m [ADDR [LEN]]}: LEN bytes (default 128) via peek, 16 per row with an ASCII column; bare m continues.
     * {@code m ADDR [LEN] = BB ...} writes the bytes through the Bus, repeated over LEN bytes; an Exit device write exits.
     */
    private Integer memory(String[] w) throws Mistake {
        int eq = List.of(w).indexOf("=");
        if (eq >= 0) {
            if (eq < 2 || eq > 3 || eq == w.length - 1) throw new Mistake("usage: m ADDR [LEN] = BB ...");
            int a = address(w[1]);
            int[] bytes = new int[w.length - eq - 1];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = value(w[eq + 1 + i]);
                if (bytes[i] >>> 8 != 0) throw new Mistake(String.format("%s: $%X is not a byte", w[eq + 1 + i], bytes[i]));
            }
            int len = eq == 3 ? count(w[2], 0x10000) : bytes.length;
            for (int i = 0; i < len; i++) machine.write(a + i & 0xFFFF, bytes[i % bytes.length]);
            return machine.pendingExit();
        }
        if (w.length > 3) throw new Mistake("usage: m [ADDR [LEN]] | m ADDR [LEN] = BB ...");
        int a = w.length > 1 ? address(w[1]) : dumpNext != null ? dumpNext : machine.cpu.pc;
        int len = w.length > 2 ? count(w[2], 0x10000) : 0x80;
        for (int row = 0; row < len; row += 16) {
            var hex = new StringBuilder();
            var ascii = new StringBuilder();
            for (int i = row; i < Math.min(len, row + 16); i++) {
                int b = machine.bus.peek(a + i & 0xFFFF);
                hex.append(String.format("%02X ", b));
                ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            out.println(String.format("%04X  %-48s %s", a + row & 0xFFFF, hex, ascii));
        }
        out.flush();
        dumpNext = a + len & 0xFFFF;
        return null;
    }

    /** {@code d [ADDR [N]]}: N instructions (default 16) via peek in the listing layout, labels on their own line; bare d continues. */
    private Integer disassemble(String[] w) throws Mistake {
        if (w.length > 3) throw new Mistake("usage: d [ADDR [N]]");
        int a = w.length > 1 ? address(w[1]) : listNext != null ? listNext : machine.cpu.pc;
        int n = w.length > 2 ? count(w[2], 0x10000) : 0x10;
        IntUnaryOperator peek = machine.bus::peek;
        int to = a;
        for (int i = 0; i < n && to - a < 0x10000 - 2; i++) to += Disassembler.decode(peek, to).bytes().length; // sweep takes at most $10000 bytes
        for (var line : Disassembler.sweep(peek, a, to, symbols)) {
            if (line instanceof Disassembler.Label l) out.println(String.format("%04X  %-8s  %s:", l.addr(), "", l.name()));
            if (line instanceof Disassembler.Code c) out.println(code(c));
        }
        out.flush();
        listNext = to & 0xFFFF;
        return null;
    }

    /** A {@link #value} from 1 to {@code max}. */
    private int count(String s, int max) throws Mistake {
        int v = value(s);
        if (v < 1 || v > max) throw new Mistake(String.format("%s: count must be 1 to $%X", s, max));
        return v;
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
            if (line instanceof Disassembler.Code c) return code(c);
        throw new AssertionError("sweep of one instruction has no code");
    }

    /** {@code ADDR  BYTES     SOURCE[ ; note]}, the Assembler listing layout. */
    private static String code(Disassembler.Code c) {
        var hex = new StringBuilder();
        for (int b : c.bytes()) hex.append(hex.isEmpty() ? "" : " ").append(String.format("%02X", b));
        String note = c.note() == null ? "" : " ; " + c.note();
        return String.format("%04X  %-8s  %s%s", c.addr(), hex, c.text(), note).stripTrailing();
    }
}
