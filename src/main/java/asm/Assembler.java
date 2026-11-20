package asm;

import static asm.Lexer.Type.*;
import static cpu.Mode.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import asm.Fixup.Kind;
import asm.Lexer.Token;
import cpu.Mode;
import cpu.Opcode;
import cpu.Opcodes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Assembler: one pass that builds Sections with Fixups for forward references, then a resolve
 * step that fills the Fixups and lays the Sections into one flat image (ADR 0001).
 */
public final class Assembler {
    /** Reads a source file (the root now, included files later). */
    @FunctionalInterface
    public interface Reader {
        byte[] read(Path path) throws IOException;
    }

    public sealed interface Result permits Output, Errors {}

    /** Flat binary loading at {@code origin}, listing text and symbol file text. */
    public record Output(int origin, byte[] binary, String listing, String symbols) implements Result {}

    /** Every error of the run, each formatted {@code file:line:col: error: msg}, source line, caret. */
    public record Errors(List<String> messages) implements Result {}

    /** (mnemonic, mode) to opcode, derived from the shared table. */
    private static final Map<String, Map<Mode, Integer>> OPCODES = new HashMap<>();

    static {
        for (int i = 0; i < 256; i++) {
            Opcode o = Opcodes.get(i);
            if (o != null) OPCODES.computeIfAbsent(o.mnemonic(), k -> new EnumMap<>(Mode.class)).put(o.mode(), i);
        }
    }

    private record Line(String text, int addr, Section section, int start, int length) {}

    private final List<Section> sections = new ArrayList<>();
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private Section section; // null before the first *=

    private Loc loc;
    private List<Token> toks;
    private int pos;

    private Assembler() {}

    public static Result assemble(Path root, Reader reader) throws IOException {
        var a = new Assembler();
        a.pass(root, new String(reader.read(root), UTF_8));
        return a.resolve();
    }

    private void pass(Path file, String source) {
        var texts = new ArrayList<>(List.of(source.split("\n", -1)));
        if (texts.getLast().isEmpty()) texts.removeLast();
        for (int n = 0; n < texts.size(); n++) {
            String text = texts.get(n).endsWith("\r") ? texts.get(n).substring(0, texts.get(n).length() - 1) : texts.get(n);
            loc = new Loc(file, n + 1, 1, text);
            Section before = section;
            int start = before == null ? 0 : before.bytes.size();
            try {
                toks = Lexer.lex(loc);
                pos = 0;
                statement();
            } catch (AsmException e) {
                errors.add(e.loc.error(e.getMessage()));
            }
            if (section != before) start = 0;
            int length = section == null ? 0 : section.bytes.size() - start;
            int addr = section == null ? -1 : section.origin + start;
            if (length > 0 && section.pc() > 0x10000) errors.add(loc.error("code runs past $FFFF"));
            lines.add(new Line(text, addr, section, start, length));
        }
    }

    // ---- statements

    private void statement() {
        if (peek().type() == IDENT && peek(1).is(":")) {
            Token label = next();
            pos++;
            requireOrigin(label);
            define(label, section.pc(), true);
        }
        if (peek().type() == END) return;
        Token t = next();
        if (t.type() == IDENT && peek().is("=")) {
            pos++;
            define(t, resolved(expr()), false);
            expectEnd();
        } else if (t.is("*") && peek().is("=")) {
            pos++;
            Token at = peek();
            int origin = resolved(expr());
            if (origin < 0 || origin > 0xFFFF) throw err(at, "origin " + show(origin) + " is outside $0000-$FFFF");
            expectEnd();
            section = new Section(origin);
            sections.add(section);
        } else if (t.type() == DIRECTIVE) {
            directive(t);
        } else if (t.type() == IDENT) {
            instruction(t);
        } else {
            throw err(t, "expected a label, instruction or directive");
        }
    }

    private void directive(Token d) {
        Kind kind = switch (d.text().toLowerCase(Locale.ROOT)) {
            case ".byte" -> Kind.BYTE;
            case ".word" -> Kind.WORD;
            default -> throw err(d, "unknown directive '" + d.text() + "'");
        };
        requireOrigin(d);
        for (;;) {
            Token at = peek();
            field(kind, expr(), at);
            if (!peek().is(",")) break;
            pos++;
        }
        expectEnd();
    }

    private void instruction(Token m) {
        String name = m.text().toUpperCase(Locale.ROOT);
        Map<Mode, Integer> modes = OPCODES.get(name);
        if (modes == null) throw err(m, "unknown instruction '" + m.text() + "'");
        requireOrigin(m);
        Token at = peek();
        if (at.type() == END) {
            Mode mode = modes.containsKey(IMP) ? IMP : ACC;
            if (!modes.containsKey(mode)) throw err(at, name + " needs an operand");
            emit(modes.get(mode));
            return;
        }
        if (modes.containsKey(IMP)) throw err(at, name + " takes no operand");
        if (modes.containsKey(ACC) && isRegister(at, "A") && peek(1).type() == END) {
            emit(modes.get(ACC));
            return;
        }
        if (at.is("#")) {
            pos++;
            Value v = expr();
            expectEnd();
            encode(name, modes, IMM, v, at);
        } else if (at.is("(") && indirect()) {
            pos++;
            Value v = expr();
            Mode mode;
            if (peek().is(",")) {
                pos++;
                expectRegister("X");
                expect(")");
                mode = IZX;
            } else {
                expect(")");
                if (peek().is(",")) {
                    pos++;
                    expectRegister("Y");
                    mode = IZY;
                } else {
                    mode = IND;
                }
            }
            expectEnd();
            encode(name, modes, mode, v, at);
        } else {
            char forced = 0;
            if ((isRegister(at, "Z") || isRegister(at, "A")) && peek(1).is(":")) {
                forced = Character.toLowerCase(at.text().charAt(0));
                pos += 2;
            }
            Value v = expr();
            String index = null;
            if (peek().is(",")) {
                pos++;
                Token r = next();
                if (!isRegister(r, "X") && !isRegister(r, "Y")) throw err(r, "expected X or Y");
                index = r.text().toUpperCase(Locale.ROOT);
            }
            expectEnd();
            if (modes.containsKey(REL)) {
                if (forced != 0 || index != null) throw err(at, name + " takes a plain target address");
                encode(name, modes, REL, v, at);
                return;
            }
            Mode zp = index == null ? ZP : index.equals("X") ? ZPX : ZPY;
            Mode abs = index == null ? ABS : index.equals("X") ? ABX : ABY;
            boolean fitsZp = v.resolved() && v.offset() >= 0 && v.offset() < 0x100;
            Mode mode = forced == 'z' ? zp
                    : forced == 'a' ? abs
                    : modes.containsKey(zp) && (fitsZp || v.resolved() && !modes.containsKey(abs)) ? zp : abs;
            encode(name, modes, mode, v, at);
        }
    }

    /** At a '(': indirect if its matching ')' ends the operand or is followed by ",Y"; else grouping. */
    private boolean indirect() {
        int depth = 0;
        for (int i = pos; i < toks.size(); i++) {
            if (toks.get(i).is("(")) depth++;
            if (toks.get(i).is(")") && --depth == 0) {
                Token a = toks.get(i + 1);
                return a.type() == END
                        || a.is(",") && isRegister(toks.get(i + 2), "Y") && toks.get(i + 3).type() == END;
            }
        }
        return false;
    }

    private void encode(String name, Map<Mode, Integer> modes, Mode mode, Value v, Token at) {
        Integer op = modes.get(mode);
        if (op == null) throw err(at, name + " has no " + describe(mode) + " mode");
        emit(op);
        field(mode == REL ? Kind.REL8 : mode.operandSize() == 1 ? Kind.BYTE : Kind.WORD, v, at);
    }

    private static String describe(Mode mode) {
        return switch (mode) {
            case IMM -> "immediate";
            case ZP -> "zero page";
            case ZPX -> "zero page,X";
            case ZPY -> "zero page,Y";
            case ABS -> "absolute";
            case ABX -> "absolute,X";
            case ABY -> "absolute,Y";
            case IND -> "(indirect)";
            case IZX -> "(indirect,X)";
            case IZY -> "(indirect),Y";
            default -> mode.name();
        };
    }

    // ---- expressions: + - and parentheses

    private Value expr() {
        Value v = term();
        while (peek().is("+") || peek().is("-")) {
            Token op = next();
            Value r = term();
            if (!r.resolved() && (op.is("-") || !v.resolved()))
                throw err(op, "define " + r.symbol() + " before this use");
            v = new Value(v.resolved() ? r.symbol() : v.symbol(), op.is("+") ? v.offset() + r.offset() : v.offset() - r.offset());
        }
        return v;
    }

    private Value term() {
        Token t = next();
        if (t.type() == NUMBER) return Value.of(t.value());
        if (t.type() == IDENT) {
            Symbol s = symbols.get(t.text());
            return s != null ? Value.of(s.value()) : new Value(t.text(), 0);
        }
        if (t.is("*")) {
            requireOrigin(t);
            return Value.of(section.pc());
        }
        if (t.is("(")) {
            Value v = expr();
            expect(")");
            return v;
        }
        throw err(t, "expected an expression");
    }

    private int resolved(Value v) {
        if (!v.resolved()) throw err(toks.get(pos - 1), "define " + v.symbol() + " before this use");
        return v.offset();
    }

    // ---- emission

    private void emit(int b) {
        section.bytes.write(b);
    }

    /** Emits a resolved field now, or zeros plus a Fixup for resolve to fill. */
    private void field(Kind kind, Value v, Token at) {
        int size = kind == Kind.WORD ? 2 : 1;
        int next = section.pc() + size; // REL8 is relative to the end of the branch
        if (v.resolved()) {
            for (int b : bytes(kind, v.offset(), next, loc.at(at.col()))) emit(b);
        } else {
            section.fixups.add(new Fixup(section.bytes.size(), v.symbol(), v.offset(), kind, loc.at(at.col())));
            for (int i = 0; i < size; i++) emit(0);
        }
    }

    private static int[] bytes(Kind kind, int v, int next, Loc loc) {
        return switch (kind) {
            case BYTE -> {
                if (v < -128 || v > 0xFF) throw new AsmException(loc, "value " + show(v) + " does not fit in a byte");
                yield new int[] {v & 0xFF};
            }
            case WORD -> {
                if (v < -32768 || v > 0xFFFF) throw new AsmException(loc, "value " + show(v) + " does not fit in a word");
                yield new int[] {v & 0xFF, v >> 8 & 0xFF};
            }
            case LO -> new int[] {v & 0xFF};
            case HI -> new int[] {v >> 8 & 0xFF};
            case REL8 -> {
                int d = v - next;
                if (d > 0x7FFF) d -= 0x10000; // the PC wraps at $FFFF, so a branch reaches across it
                else if (d < -0x8000) d += 0x10000;
                if (d < -128 || d > 127) throw new AsmException(loc, "branch target out of range (" + d + " bytes)");
                yield new int[] {d & 0xFF};
            }
        };
    }

    private static String show(int v) {
        return v < 0 ? String.valueOf(v) : String.format("$%X", v);
    }

    private void define(Token name, int value, boolean label) {
        if (symbols.containsKey(name.text())) throw err(name, name.text() + " is already defined");
        symbols.put(name.text(), new Symbol(name.text(), value, label, Linkage.LOCAL));
    }

    private void requireOrigin(Token t) {
        if (section == null) throw err(t, "no origin yet: set one with *= first");
    }

    // ---- tokens

    private Token peek() {
        return peek(0);
    }

    private Token peek(int ahead) {
        return toks.get(Math.min(pos + ahead, toks.size() - 1));
    }

    private Token next() {
        Token t = peek();
        if (t.type() != END) pos++;
        return t;
    }

    private void expect(String punct) {
        Token t = next();
        if (!t.is(punct)) throw err(t, "expected '" + punct + "'");
    }

    private void expectRegister(String r) {
        Token t = next();
        if (!isRegister(t, r)) throw err(t, "expected " + r);
    }

    private void expectEnd() {
        Token t = peek();
        if (t.type() != END) throw err(t, "unexpected '" + t.text() + "'");
    }

    private static boolean isRegister(Token t, String r) {
        return t.type() == IDENT && t.text().equalsIgnoreCase(r);
    }

    private AsmException err(Token t, String msg) {
        return new AsmException(loc.at(t.col()), msg);
    }

    // ---- resolve and outputs

    private Result resolve() {
        var data = new HashMap<Section, byte[]>();
        for (Section s : sections) {
            byte[] b = s.bytes.toByteArray();
            for (Fixup f : s.fixups) {
                Symbol sym = symbols.get(f.symbol());
                if (sym == null) {
                    errors.add(f.loc().error("undefined symbol " + f.symbol()));
                    continue;
                }
                int size = f.kind() == Kind.WORD ? 2 : 1;
                try {
                    int[] v = bytes(f.kind(), sym.value() + f.offset(), s.origin + f.at() + size, f.loc());
                    for (int i = 0; i < v.length; i++) b[f.at() + i] = (byte) v[i];
                } catch (AsmException e) {
                    errors.add(e.loc.error(e.getMessage()));
                }
            }
            data.put(s, b);
        }
        if (!errors.isEmpty()) return new Errors(List.copyOf(errors));

        // shortcut: later Sections overwrite earlier ones; the overlap error arrives with the rest of v1
        int lo = 0x10000, hi = 0;
        for (Section s : sections) {
            if (s.bytes.size() == 0) continue;
            lo = Math.min(lo, s.origin);
            hi = Math.max(hi, s.pc());
        }
        if (lo > hi) lo = hi = sections.isEmpty() ? 0 : sections.getFirst().origin;
        byte[] image = new byte[hi - lo];
        for (Section s : sections) System.arraycopy(data.get(s), 0, image, s.origin - lo, s.bytes.size());
        return new Output(lo, image, listing(data), symbolFile());
    }

    /** {@code ADDR  B1 B2 B3  SOURCE}; data over 3 bytes continues on lines without source. */
    private String listing(Map<Section, byte[]> data) {
        var out = new StringBuilder();
        for (Line l : lines) {
            int i = 0;
            do {
                var hex = new StringBuilder();
                for (int j = i; j < Math.min(i + 3, l.length()); j++)
                    hex.append(hex.isEmpty() ? "" : " ").append(String.format("%02X", data.get(l.section())[l.start() + j]));
                String addr = l.addr() < 0 ? "" : String.format("%04X", l.addr() + i);
                out.append(String.format("%-4s  %-8s  %s", addr, hex, i == 0 ? l.text() : "").stripTrailing()).append('\n');
                i += 3;
            } while (i < l.length());
        }
        return out.toString();
    }

    /** {@code <hex value> <name> <label|const> <global|local>}, sorted by value, ties in definition order. */
    private String symbolFile() {
        var out = new StringBuilder();
        symbols.values().stream().sorted(Comparator.comparingInt(Symbol::value)).forEach(s -> out.append(String.format(
                "%04X %s %s %s\n", s.value(), s.name(), s.label() ? "label" : "const",
                s.linkage().name().toLowerCase(Locale.ROOT))));
        return out.toString();
    }
}
