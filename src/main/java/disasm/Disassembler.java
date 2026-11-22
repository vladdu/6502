package disasm;

import cpu.Mnemonic;
import cpu.Mode;
import cpu.Opcode;
import cpu.Opcodes;
import disasm.SymbolFile.Data;
import disasm.SymbolFile.Symbol;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

/** Linear-sweep Disassembler over any byte source ({@code addr -> byte}), using the shared opcode table. */
public final class Disassembler {
    /** One decoded instruction; {@code mnemonic} null for an undocumented opcode. Branch operands are absolute targets. */
    @SuppressWarnings("ArrayRecordComponent") // never compared with equals
    public record Instruction(int addr, int[] bytes, Mnemonic mnemonic, Mode mode, int operand) {}

    /** A line of {@link #sweep}: equates for referenced labels outside the range first, then labels and code. */
    public sealed interface Line permits Equate, Label, Code {}

    /** {@code name = $XXXX}: a label outside the range that an operand names. */
    public record Equate(String name, int value) implements Line {}

    /** {@code name:} before the code at {@code addr}. */
    public record Label(int addr, String name) implements Line {}

    /** {@code text} is the source, e.g. {@code LDA #$2A}; {@code note} an optional comment, e.g. {@code undocumented}. */
    @SuppressWarnings("ArrayRecordComponent") // never compared with equals
    public record Code(int addr, int[] bytes, String text, String note) implements Line {}

    /** The most bytes a line holds: a {@code .byte} line in a data range; an instruction is at most 3. */
    public static final int LONGEST = 16;

    /** Every documented {@code "MNEMONIC MODE"} pair. */
    private static final Set<String> MODES = new HashSet<>();

    static {
        for (int i = 0; i < 256; i++) if (Opcodes.get(i) != null) MODES.add(Opcodes.get(i).mnemonic() + " " + Opcodes.get(i).mode());
    }

    private Disassembler() {}

    /** Decodes the instruction at {@code addr}; addresses wrap at $FFFF. */
    public static Instruction decode(IntUnaryOperator source, int addr) {
        int op = source.applyAsInt(addr & 0xFFFF) & 0xFF;
        Opcode o = Opcodes.get(op);
        if (o == null) return new Instruction(addr & 0xFFFF, new int[] {op}, null, null, 0);
        int[] b = new int[1 + o.mode().operandSize()];
        b[0] = op;
        for (int i = 1; i < b.length; i++) b[i] = source.applyAsInt(addr + i & 0xFFFF) & 0xFF;
        int operand = b.length == 1 ? 0 : b.length == 2 ? b[1] : b[1] | b[2] << 8;
        if (o.mode() == Mode.REL) operand = addr + 2 + (byte) operand & 0xFFFF;
        return new Instruction(addr & 0xFFFF, b, o.mnemonic(), o.mode(), operand);
    }

    /**
     * Disassembles {@code from} (inclusive) to {@code to} (exclusive, at most $10000 past {@code from}).
     * Label symbols, never consts, name address operands with an exact match; first in the list wins.
     */
    public static List<Line> sweep(IntUnaryOperator source, int from, int to, List<Symbol> symbols) {
        return sweep(source, from, to, symbols, List.of(), Set.of());
    }

    /**
     * {@link #sweep(IntUnaryOperator, int, int, List)}, with the bytes in {@code data} as {@code .byte} lines of at most
     * {@link #LONGEST} bytes, and decoding restarting at each {@code sync} address as at a label, with no label line.
     */
    public static List<Line> sweep(IntUnaryOperator source, int from, int to, List<Symbol> symbols, List<Data> data, Set<Integer> sync) {
        var defs = new HashMap<Integer, List<String>>(); // position -> labels defined there, as written
        var byValue = new HashMap<Integer, List<String>>(); // value -> label symbol names, in list order
        var scopes = new TreeMap<Integer, String>(); // position -> ordinary label opening a scope there
        var locals = new HashSet<String>(); // cheap local labels (parent@name) defined in the range
        List<Symbol> labels = symbols.stream().filter(s -> s.label() && s.value() >>> 16 == 0).toList();
        for (boolean cheap : new boolean[] {false, true}) { // ordinary labels first: they set the scopes
            for (Symbol s : labels) {
                if (s.name().contains("@") != cheap) continue;
                int at = s.value() - from & 0xFFFF;
                if (at >= to - from) continue;
                int i = s.name().indexOf('@');
                if (cheap && !s.name().substring(0, i).equals(scope(scopes, from + at))) continue;
                if (cheap) locals.add(s.name());
                else scopes.put(from + at, s.name());
                defs.computeIfAbsent(from + at, k -> new ArrayList<>()).add(cheap ? s.name().substring(i) : s.name());
            }
        }
        for (Symbol s : labels)
            if (!s.name().contains("@") || locals.contains(s.name())) byValue.computeIfAbsent(s.value(), k -> new ArrayList<>()).add(s.name());
        var equates = new LinkedHashMap<String, Integer>();
        var lines = new ArrayList<Line>();
        for (int pos = from; pos < to; ) {
            for (String name : defs.getOrDefault(pos, List.of())) lines.add(new Label(pos & 0xFFFF, name));
            if (isData(data, pos)) {
                int end = pos + 1;
                while (end < Math.min(to, pos + LONGEST) && isData(data, end) && !defs.containsKey(end) && !sync.contains(end & 0xFFFF)) end++;
                int[] b = new int[end - pos];
                for (int i = 0; i < b.length; i++) b[i] = source.applyAsInt(pos + i & 0xFFFF) & 0xFF;
                lines.add(data(pos & 0xFFFF, b, null));
                pos = end;
                continue;
            }
            Instruction in = decode(source, pos);
            int len = in.bytes().length;
            int label = pos + 1;
            while (label < pos + len && !defs.containsKey(label) && !sync.contains(label & 0xFFFF) && !isData(data, label)) label++;
            if (label < pos + len) {
                len = label - pos; // restart at the label; the bytes before it are data
                lines.add(data(in.addr(), Arrays.copyOf(in.bytes(), len), null));
            } else if (in.mnemonic() == null) {
                lines.add(data(in.addr(), in.bytes(), "undocumented"));
            } else if (pos + len > to) {
                len = to - pos;
                lines.add(data(in.addr(), Arrays.copyOf(in.bytes(), len), null));
            } else {
                String name = null;
                if (in.mode() != Mode.IMP && in.mode() != Mode.ACC && in.mode() != Mode.IMM) {
                    for (String n : byValue.getOrDefault(in.operand(), List.of())) {
                        if (n.matches("(?i)[AXY]")) continue; // as an operand it would read as a register (ASL A)
                        int i = n.indexOf('@');
                        if (i < 0 || n.substring(0, i).equals(scope(scopes, pos))) {
                            name = i < 0 ? n : n.substring(i);
                            break;
                        }
                    }
                    int at = in.operand() - from & 0xFFFF;
                    if (name != null && at >= to - from) equates.put(name, in.operand());
                    // the Assembler makes a forward reference absolute
                    boolean zp = in.mode() == Mode.ZP || in.mode() == Mode.ZPX || in.mode() == Mode.ZPY;
                    if (name != null && zp && at < to - from && from + at > pos) name = "z:" + name;
                }
                lines.add(new Code(in.addr(), in.bytes(), text(in, name), null));
            }
            pos += len;
        }
        var out = new ArrayList<Line>();
        equates.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> out.add(new Equate(e.getKey(), e.getValue())));
        out.addAll(lines);
        return out;
    }

    private static boolean isData(List<Data> data, int pos) {
        for (Data d : data) if (d.contains(pos)) return true;
        return false;
    }

    /** The ordinary label whose scope covers {@code pos}, or null. */
    private static String scope(NavigableMap<Integer, String> scopes, int pos) {
        var e = scopes.floorEntry(pos);
        return e == null ? null : e.getValue();
    }

    /** A {@code .byte} line; a run of 3 or more printable characters shows as a string. */
    private static Code data(int addr, int[] bytes, String note) {
        var items = new ArrayList<String>();
        for (int i = 0; i < bytes.length; ) {
            int run = i;
            while (run < bytes.length && printable(bytes[run])) run++;
            if (run - i < 3) {
                items.add(hex2(bytes[i++]));
                continue;
            }
            var str = new StringBuilder("\"");
            while (i < run) str.append((char) bytes[i++]);
            items.add(str.append('"').toString());
        }
        return new Code(addr, bytes, ".byte " + String.join(", ", items), note);
    }

    /** Printable ASCII that needs no escape in an Assembler string. */
    private static boolean printable(int b) {
        return b >= 0x20 && b < 0x7F && b != '"' && b != '\\';
    }

    /** Source text, with {@code name} for the operand if not null. */
    private static String text(Instruction in, String name) {
        Mode mode = in.mode();
        String v = name != null ? name
                : mode.operandSize() == 0 ? ""
                : mode.operandSize() == 1 && mode != Mode.REL ? hex2(in.operand()) // a branch shows its absolute target
                : hex4(in.operand());
        // the Assembler picks zero page for a resolved operand below $100 where the mnemonic has that form
        Mode zp = mode == Mode.ABS ? Mode.ZP : mode == Mode.ABX ? Mode.ZPX : mode == Mode.ABY ? Mode.ZPY : null;
        if (zp != null && in.operand() < 0x100 && MODES.contains(in.mnemonic() + " " + zp)) v = "a:" + v;
        String m = in.mnemonic().name();
        return switch (mode) {
            case IMP -> m;
            case ACC -> m + " A";
            case IMM -> m + " #" + v;
            case ZP, ABS, REL -> m + " " + v;
            case ZPX, ABX -> m + " " + v + ",X";
            case ZPY, ABY -> m + " " + v + ",Y";
            case IZX -> m + " (" + v + ",X)";
            case IZY -> m + " (" + v + "),Y";
            case IND -> m + " (" + v + ")";
        };
    }

    private static String hex2(int v) {
        return String.format("$%02X", v);
    }

    private static String hex4(int v) {
        return String.format("$%04X", v);
    }

    /** A {@link Label} or {@link Code} line as the Monitor's {@code d} prints it, in the Assembler listing layout; null for an {@link Equate}. */
    public static String listing(Line line) {
        return switch (line) {
            case Equate e -> null;
            case Label l -> String.format("%04X  %-8s  %s:", l.addr(), "", l.name());
            case Code c -> {
                var hex = new StringBuilder();
                for (int b : c.bytes()) hex.append(hex.isEmpty() ? "" : " ").append(String.format("%02X", b));
                String note = c.note() == null ? "" : " ; " + c.note();
                yield String.format("%04X  %-8s  %s%s", c.addr(), hex, c.text(), note).stripTrailing();
            }
        };
    }

    /** CLI source form of an image loaded at {@code org}; reassembles to identical bytes. */
    public static String source(byte[] image, int org, List<Symbol> symbols) {
        return source(image, org, symbols, List.of());
    }

    /** {@link #source(byte[], int, List)} with the bytes in {@code data} as {@code .byte} lines. */
    public static String source(byte[] image, int org, List<Symbol> symbols, List<Data> data) {
        var out = new StringBuilder(String.format("*= $%04X\n", org));
        for (Line l : sweep(a -> (a - org & 0xFFFF) < image.length ? image[a - org & 0xFFFF] : 0, org, org + image.length, symbols, data, Set.of())) {
            if (l instanceof Equate e) out.append(String.format("%s = $%04X\n", e.name(), e.value()));
            if (l instanceof Label label) out.append(label.name()).append(":\n");
            if (l instanceof Code c) {
                var hex = new StringBuilder();
                for (int b : c.bytes()) hex.append(' ').append(String.format("%02X", b));
                out.append(String.format("        %-23s ; %04X %s%s", c.text(), c.addr(), hex, c.note() == null ? "" : "  " + c.note())).append('\n');
            }
        }
        return out.toString();
    }
}
