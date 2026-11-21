package cli;

import cpu.Cpu;
import disasm.Disassembler;
import disasm.SymbolFileException;
import disasm.SymbolTable;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;
import machine.LoadException;
import machine.Machine;
import machine.StopReason;
import machine.Watchpoint;
import picocli.CommandLine.TypeConversionException;

/** The Monitor's command interpreter: takes one line, runs its command and prints to a PrintWriter; front ends read the lines. */
public final class Interpreter {
    /** The command names, for a front end's completion. */
    public static final List<String> COMMANDS = List.of("go", "continue", "step", "s", "next", "n", "until", "b", "w", "r", "m", "d",
            "load", "syms", "reset", "nmi", "disk", "restart", "info", "speed", "quit", "q", "help");
    private static final String HELP = """
            go [ADDR] | continue    run (from ADDR) to the next stop
            step [N] | s [N]        N Steps (default 1); an empty line repeats it
            next | n                step, but over a JSR to its return; an empty line repeats it
            until ADDR              run until PC reaches ADDR, or to any other stop first
            b ADDR | b del ADDR     add or remove a breakpoint; bare b lists them
            w [r|rw] ADDR [LEN]     watch LEN bytes (default 1) for writes, r reads or rw both
            w del ADDR              remove the Watchpoint starting at ADDR; bare w lists them
            r [NAME=VALUE ...]      show registers; set pc a x y s p, or a flag n v d i z c
            m [ADDR [LEN]]          dump LEN bytes (default 128); bare m continues
            m ADDR [LEN] = BB ...   write the bytes through the Bus, repeated over LEN bytes
            d [ADDR [N]]            disassemble N instructions (default 16); bare d continues
            load FILE [ADDR]        copy FILE to ADDR (default $0200), and its .sym if there
            syms FILE               merge a symbol file; later names win
            reset                   hardware Reset: Devices reset, PC from $FFFC, memory kept
            nmi                     press the NMI button: the next Step is the NMI entry
            disk [FILE [ro]]        show the Disk image, or insert FILE (ro: write-protected)
            disk eject | ro | rw    eject, or set or clear the write-protect tab
            restart                 start again as launched: RAM zeroed, images and symbols reread
            info                    Console, Timer, Disk, speed, loaded images and symbol files
            speed [MHZ | off]       show or set the throttle (e.g. 1, 0.985), from the next run
            quit [CODE] | q [CODE]  exit the Simulator with CODE (default 0); Ctrl-D quits too
            help                    this list
            Numbers are hex ($ optional), #10 is decimal; symbols as sym, sym+N or sym-N.""";

    private final Machine machine;
    private final Monitor.ConsoleOut console;
    private SymbolTable symbols;
    private final Launch launch;
    /** Launched images, then those of {@code load}; a restart drops the latter. */
    private final List<String> images;
    private final Set<Integer> breakpoints = new TreeSet<>();
    /** The Watchpoints by start address, installed in the Machine after every change. */
    private final Map<Integer, Watchpoint> watchpoints = new TreeMap<>();
    /** The front end's hooks: hand the host Console to the program before a run, take it back after. */
    private final Runnable resume, pause;
    /** Where a bare {@code m} continues; null before the first dump. */
    private Integer dumpNext;
    /** Where a bare {@code d} continues; null before the first listing. */
    private Integer listNext;
    /** The reason line of the last Stop display; null before the first. */
    private String lastStop;
    /** The last command if an empty line repeats it ({@code step}), else null. */
    private String[] repeat;
    private final PrintWriter out;

    Interpreter(Machine machine, PrintWriter out, Monitor.ConsoleOut console, SymbolTable symbols, Launch launch, Runnable resume, Runnable pause) {
        this.machine = machine;
        this.out = out;
        this.console = console;
        this.symbols = symbols;
        this.launch = launch;
        this.images = new ArrayList<>(launch.images());
        this.resume = resume;
        this.pause = pause;
    }

    /** What a line did that its front end acts on; {@link #execute} returns null when the session just goes on. */
    public sealed interface Result {}

    /** {@code quit [CODE]}: the front end ends the session with CODE. */
    public record Quit(int code) implements Result {}

    /** A run stopped, or an {@code m} write hit the Exit device; an Exit gets no stop display. */
    public record Stopped(StopReason reason) implements Result {}

    /** A command the user typed wrong: its message is printed and the session goes on. */
    private static final class Mistake extends Exception {
        Mistake(String message) {
            super(message, null, false, false);
        }
    }

    /** The stop display for {@code reason}, or for Paused before the first Step when null. */
    public void show(StopReason reason) {
        display(reason == null ? "Paused" : reason(reason));
    }

    /** The breakpoints, ascending. */
    public List<Integer> breakpoints() {
        return List.copyOf(breakpoints);
    }

    /** The Watchpoints, by start address. */
    public List<Watchpoint> watchpoints() {
        return List.copyOf(watchpoints.values());
    }

    /** The Symbol table now; {@code restart} replaces it, {@code load} and {@code syms} add files to it. */
    public SymbolTable symbols() {
        return symbols;
    }

    /** The reason line of the last Stop display ("Paused", "Reset", "Restart" or a Stop's), or null before the first. */
    public String lastStop() {
        return lastStop;
    }

    /** Runs one line; an empty line repeats a {@code step} or {@code next} that came just before. */
    public Result execute(String line) {
        String[] w = line.isBlank() && repeat != null ? repeat : line.trim().split("\\s+");
        repeat = null;
        try {
            return switch (w[0]) {
                case "" -> null;
                case "go", "continue" -> go(w);
                case "step", "s" -> {
                    repeat = w;
                    yield step(w);
                }
                case "next", "n" -> {
                    repeat = w;
                    yield next(w);
                }
                case "until" -> until(w);
                case "b" -> breakpoint(w);
                case "w" -> watchpoint(w);
                case "r" -> registers(w);
                case "m" -> memory(w);
                case "d" -> disassemble(w);
                case "reset" -> {
                    if (w.length > 1) throw new Mistake("usage: reset");
                    machine.reset();
                    display("Reset");
                    yield null;
                }
                case "nmi" -> {
                    if (w.length > 1) throw new Mistake("usage: nmi");
                    machine.pressNmi();
                    yield null;
                }
                case "disk" -> disk(w);
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
        } catch (Mistake e) {
            out.println(e.getMessage());
            out.flush();
            return null;
        }
    }

    /** {@code restart}: the Launch again on a cleared Machine; what {@code load} and {@code syms} added is gone. */
    private Result restart(String[] w) throws Mistake {
        if (w.length > 1) throw new Mistake("usage: restart");
        try {
            symbols = launch.restart();
        } catch (LoadException e) {
            throw new Mistake(e.getMessage());
        }
        images.clear();
        images.addAll(launch.images());
        display("Restart");
        return null;
    }

    /** {@code disk} shows, {@code disk FILE [ro]} inserts, {@code disk eject} ejects, {@code disk ro|rw} sets the write-protect tab. */
    private Result disk(String[] w) throws Mistake {
        if (w.length > 3 || (w.length == 3 && !w[2].equals("ro"))) throw new Mistake("usage: disk [FILE [ro] | eject | ro | rw]");
        var disk = machine.disk;
        Path image = disk.state().image();
        if (w.length == 1) out.println(disk.state().describe());
        else if (w.length == 2 && w[1].equals("eject")) disk.eject();
        else if (w.length == 2 && (w[1].equals("ro") || w[1].equals("rw"))) {
            if (image == null) throw new Mistake("no Disk image");
            try {
                disk.writeProtect(w[1].equals("ro"));
            } catch (AccessDeniedException e) {
                throw new Mistake(image + ": not writable");
            } catch (IOException e) {
                throw new Mistake("cannot open " + image + ": " + e);
            }
        } else {
            Path file = Path.of(w[1]);
            boolean ro = w.length == 3;
            try {
                disk.attach(file, ro);
            } catch (IOException e) {
                throw new Mistake("cannot read " + file + ": " + e);
            } catch (LoadException e) {
                throw new Mistake(file + ": " + e.getMessage());
            }
            if (!ro && disk.readOnly()) out.println("warning: " + file + ": not writable, Disk mounted read-only");
        }
        out.flush();
        return null;
    }

    /** {@code load FILE [ADDR]}: copies the bytes (default $0200), registers unchanged; a sibling .sym loads too. */
    private Result load(String[] w) throws Mistake {
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
        try {
            symbols.addSiblingOf(file);
        } catch (SymbolFileException e) {
            throw new Mistake(e.getMessage());
        }
        return null;
    }

    /** Adds a symbol file to the Symbol table; a malformed file is refused whole. */
    private void symbols(Path file) throws Mistake {
        try {
            symbols.add(file);
        } catch (SymbolFileException e) {
            throw new Mistake(e.getMessage());
        }
    }

    /** {@code info}: each Device's own description, then speed, images and symbol files. */
    private Result info(String[] w) throws Mistake {
        if (w.length > 1) throw new Mistake("usage: info");
        machine.describe().forEach(out::println);
        out.println(speed());
        out.println("Images: " + (images.isEmpty() ? "none" : String.join(", ", images)));
        out.println("Symbol files: " + (symbols.files().isEmpty() ? "none" : String.join(", ", symbols.files())));
        out.flush();
        return null;
    }

    /** {@code speed} shows, {@code speed MHZ} sets, {@code speed off} turns off the throttle; the next run paces afresh anyway. */
    private Result speed(String[] w) throws Mistake {
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
    private Result quit(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: quit [CODE]");
        int code = w.length == 2 ? value(w[1]) : 0;
        if (code >>> 8 != 0) throw new Mistake(String.format("%s: exit code $%X is not a byte", w[1], code));
        return new Quit(code);
    }

    /** {@code go [ADDR]}: runs (from ADDR if given) to the next stop. */
    private Result go(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: go [ADDR]");
        if (w.length == 2) machine.cpu.pc = address(w[1]);
        resumeHost();
        StopReason r = machine.run(breakpoints);
        return stopped(r, reason(r));
    }

    /** Hands the Terminal back for a run, first dropping a Ctrl-] that came as the last run stopped for another reason. */
    private void resumeHost() {
        machine.breakRequested = false;
        resume.run();
    }

    /** {@code step [n]}: n Steps (default 1), stopping early on any Stop but a breakpoint. */
    private Result step(String[] w) throws Mistake {
        if (w.length > 2) throw new Mistake("usage: step [N]");
        int n = w.length == 2 ? value(w[1]) : 1;
        resumeHost();
        StopReason r = machine.run(Set.of(), n);
        return stopped(r, reason(r));
    }

    /**
     * {@code next}: on a JSR, runs to its return (PC+3 with S back at least where it was, so a recursive call
     * returning there doesn't count); otherwise one {@code step}. Any other stop ends it.
     */
    private Result next(String[] w) throws Mistake {
        if (w.length > 1) throw new Mistake("usage: next");
        Cpu c = machine.cpu;
        if (machine.bus.peek(c.pc) != 0x20) return step(new String[] {"step"});
        int ret = c.pc + 3 & 0xFFFF, s = c.s;
        var stops = new TreeSet<>(breakpoints);
        stops.add(ret);
        resumeHost();
        StopReason r;
        do r = machine.run(stops);
        while (r instanceof StopReason.Breakpoint(int pc) && pc == ret && !breakpoints.contains(ret) && c.s < s);
        return stopped(r, r instanceof StopReason.Breakpoint(int pc) && pc == ret && !breakpoints.contains(ret) ? "Step" : reason(r));
    }

    /** {@code until ADDR}: runs with a one-shot breakpoint at ADDR that goes with the run; reaching it isn't shown as a breakpoint. */
    private Result until(String[] w) throws Mistake {
        if (w.length != 2) throw new Mistake("usage: until ADDR");
        int a = address(w[1]);
        var stops = new TreeSet<>(breakpoints);
        stops.add(a);
        resumeHost();
        StopReason r = machine.run(stops);
        return stopped(r, r instanceof StopReason.Breakpoint(int pc) && pc == a && !breakpoints.contains(a)
                ? String.format("Reached $%04X", a) + label(a) : reason(r));
    }

    /** Takes the host Console back after a run and shows its stop display as {@code text}, but not for an Exit. */
    private Result stopped(StopReason r, String text) {
        pause.run();
        if (!(r instanceof StopReason.Exited)) display(text);
        return new Stopped(r);
    }

    /** {@code b ADDR} adds, {@code b del ADDR} removes, bare {@code b} lists. */
    private Result breakpoint(String[] w) throws Mistake {
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

    /** {@code w [r|rw] ADDR [LEN]} adds or replaces the one at ADDR, {@code w del ADDR} removes, bare {@code w} lists. */
    private Result watchpoint(String[] w) throws Mistake {
        String usage = "usage: w [r|rw] ADDR [LEN] | w del ADDR";
        if (w.length == 1) {
            if (watchpoints.isEmpty()) out.println("no watchpoints");
            for (Watchpoint p : watchpoints.values()) {
                int end = p.start() + p.length() - 1;
                out.println(String.format("$%04X", p.start()) + (end == p.start() ? "" : String.format("-$%04X", end))
                        + (p.reads() ? p.writes() ? " rw" : " r" : " w") + label(p.start()));
            }
        } else if (w[1].equals("del")) {
            if (w.length != 3) throw new Mistake(usage);
            int a = address(w[2]);
            if (watchpoints.remove(a) == null) out.println(String.format("no watchpoint at $%04X", a));
        } else {
            boolean reads = w[1].equals("r") || w[1].equals("rw"), writes = !w[1].equals("r");
            int at = reads ? 2 : 1;
            if (w.length < at + 1 || w.length > at + 2) throw new Mistake(usage);
            int a = address(w[at]);
            int len = w.length > at + 1 ? count(w[at + 1], 0x10000 - a) : 1;
            watchpoints.put(a, new Watchpoint(a, len, reads, writes));
        }
        machine.watch(watchpoints.values());
        out.flush();
        return null;
    }

    /** {@code r} shows the registers; {@code r pc=0213 a=ff p=24 c=1 ...} sets registers and single flags first. */
    private Result registers(String[] w) throws Mistake {
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
                case "p" -> c.p = (v & ~Cpu.B) | Cpu.U;
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
     * {@code m ADDR [LEN] = BB ...} writes the bytes through the Bus, repeated over LEN bytes; an Exit device write exits at once.
     */
    private Result memory(String[] w) throws Mistake {
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
            for (int i = 0; i < len; i++) {
                Integer exit = machine.write(a + i & 0xFFFF, bytes[i % bytes.length]);
                if (exit != null) return new Stopped(new StopReason.Exited(exit));
            }
            return null;
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
    private Result disassemble(String[] w) throws Mistake {
        if (w.length > 3) throw new Mistake("usage: d [ADDR [N]]");
        int a = w.length > 1 ? address(w[1]) : listNext != null ? listNext : machine.cpu.pc;
        int n = w.length > 2 ? count(w[2], 0x10000) : 0x10;
        IntUnaryOperator peek = machine.bus::peek;
        int to = a;
        for (int i = 0; i < n && to - a < 0x10000 - 2; i++) to += Disassembler.decode(peek, to).bytes().length; // sweep takes at most $10000 bytes
        for (var line : Disassembler.sweep(peek, a, to, symbols.symbols())) {
            if (!(line instanceof Disassembler.Equate)) out.println(Disassembler.listing(line));
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

    /** {@link #value(String, SymbolTable)} over this session's symbols. */
    private int value(String s) throws Mistake {
        try {
            return value(s, symbols);
        } catch (IllegalArgumentException e) {
            throw new Mistake(e.getMessage());
        }
    }

    /**
     * A Monitor value: bare numbers are hex ({@code $} optional), {@code #} decimal; a symbol, optionally {@code sym+n} / {@code sym-n}.
     * Throws IllegalArgumentException saying which part is neither a number nor a symbol.
     */
    public static int value(String s, SymbolTable symbols) {
        Integer v = symbols.value(s);
        if (v != null) return v;
        if (s.matches("\\$?[0-9A-Fa-f]{1,8}")) return (int) Long.parseLong(s.replace("$", ""), 16);
        if (s.matches("#[0-9]{1,9}")) return Integer.parseInt(s.substring(1));
        int at = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
        Integer base = at > 0 ? symbols.value(s.substring(0, at)) : null;
        if (base != null) {
            int n = value(s.substring(at + 1), symbols);
            return base + (s.charAt(at) == '+' ? n : -n);
        }
        throw new IllegalArgumentException("'" + s + "' is not a number or symbol");
    }

    private String reason(StopReason r) {
        String s = r.toString();
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        if (r instanceof StopReason.Breakpoint(int pc)) s += label(pc);
        return s;
    }

    /** {@code " (name)"} for the first label symbol at {@code addr}, else empty. */
    private String label(int addr) {
        String name = symbols.label(addr);
        return name == null ? "" : " (" + name + ")";
    }

    /** The stop display: reason, registers, current instruction. */
    private void display(String reason) {
        lastStop = reason;
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
        for (var line : Disassembler.sweep(peek, addr, addr + len, symbols.symbols()))
            if (line instanceof Disassembler.Code c) return Disassembler.listing(c);
        throw new AssertionError("sweep of one instruction has no code");
    }
}
