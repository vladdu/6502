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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * The Assembler: one pass that builds Sections with Fixups for forward references, then a resolve
 * step that fills the Fixups and lays the Sections into one flat image (ADR 0001).
 */
public final class Assembler {
    /** Reads a source file: the root, an {@code .include}d file or an {@code .incbin}'d binary. */
    @FunctionalInterface
    public interface Reader {
        byte[] read(Path path) throws IOException;
    }

    public sealed interface Result permits Output, Errors {}

    /** Flat binary loading at {@code origin}, listing text and symbol file text. */
    @SuppressWarnings("ArrayRecordComponent") // never compared with equals
    public record Output(int origin, byte[] binary, String listing, String symbols) implements Result {}

    /** Every error of the run, each formatted {@code file:line:col: error: msg}, source line, caret. */
    public record Errors(List<String> messages) implements Result {}

    /** (mnemonic, mode) to opcode, derived from the shared table. */
    private static final Map<String, Map<Mode, Integer>> OPCODES = new HashMap<>();

    static {
        for (int i = 0; i < 256; i++) {
            Opcode o = Opcodes.get(i);
            if (o != null) OPCODES.computeIfAbsent(o.mnemonic().name(), _ -> new EnumMap<>(Mode.class)).put(o.mode(), i);
        }
    }

    /** {@code sized}: listed as {@code (N bytes)} instead of the bytes; {@code data}: its bytes come from a data directive. */
    private record Line(Path file, String text, int addr, Section section, int start, int length, boolean sized, boolean data) {}

    private final Map<String, Section> sections = new LinkedHashMap<>(); // in declaration order, a namespace apart from the symbols
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Fixup> fixups = new ArrayList<>(); // of every Section, in source order
    private final Set<Fixup> guessedAbsolute = new HashSet<>(); // operands that get an error if they land in zero page
    private Section section; // null before the first .section
    private Map<Section, String> scopes = new HashMap<>(); // this file's last ordinary label in each Section, parent of cheap local labels
    private boolean sized; // the current line lists as (N bytes)
    private boolean data; // the current line's bytes are data, not code
    private Reader reader;
    private final List<Path> including = new ArrayList<>(); // the .include chain, root first
    private Path include; // set by .include, read after its own line is listed
    private String includeText;

    private Loc loc;
    private List<Token> toks;
    private int pos;

    private Assembler() {}

    public static Result assemble(Path root, Reader reader) throws IOException {
        var a = new Assembler();
        a.reader = reader;
        a.pass(root, new String(reader.read(root), UTF_8));
        return a.resolve();
    }

    private void pass(Path file, String source) {
        including.add(file.normalize());
        var texts = new ArrayList<>(List.of(source.split("\n", -1)));
        if (texts.getLast().isEmpty()) texts.removeLast();
        for (int n = 0; n < texts.size(); n++) {
            String text = texts.get(n).endsWith("\r") ? texts.get(n).substring(0, texts.get(n).length() - 1) : texts.get(n);
            loc = new Loc(file, n + 1, 1, text);
            Section before = section;
            int start = before == null ? 0 : before.bytes.size();
            sized = false;
            data = false;
            try {
                toks = Lexer.lex(loc);
                pos = 0;
                statement();
            } catch (AsmException e) {
                errors.add(e.loc.error(e.getMessage()));
            }
            if (section != before) start = section.bytes.size(); // a line that switches Sections emits nothing
            int length = section == null ? 0 : section.bytes.size() - start;
            int addr = section == null ? -1 : section.origin + start;
            if (length > 0 && section.pc() > 0x10000) errors.add(loc.error("code runs past $FFFF"));
            lines.add(new Line(file, text, addr, section, start, length, sized, data));
            if (include != null) {
                Path f = include;
                include = null;
                Section caller = section;
                var callerScopes = scopes;
                scopes = new HashMap<>();
                pass(f, includeText);
                section = caller;
                scopes = callerScopes;
            }
        }
        including.removeLast();
    }

    // ---- statements

    private void statement() {
        boolean labelled = peek().type() == IDENT && peek(1).is(":");
        int body = labelled ? 2 : 0; // checked ahead of the label, which would ask for a Section
        if (peek(body).is("*") && peek(body + 1).is("="))
            throw err(peek(body), "*= is not supported: declare a Section with .defsection and switch to it with .section");
        if (labelled) {
            Token label = next();
            pos++;
            String then = peek().text().toLowerCase(Locale.ROOT);
            if (then.equals(".section") || then.equals(".defsection"))
                throw err(label, "a label can't be on a " + then + " line: put it on the next line");
            requireSection(label);
            define(label, section.pc(), true);
            if (!label.text().startsWith("@")) scopes.put(section, label.text());
        }
        if (peek().type() == END) return;
        Token t = next();
        if (t.type() == IDENT && peek().is("=")) {
            if (t.text().startsWith("@")) throw err(t, t.text() + ": a cheap local label must be a label, not a constant");
            pos++;
            define(t, resolved(expr()), false);
            expectEnd();
        } else if (t.type() == DIRECTIVE) {
            directive(t);
        } else if (t.type() == IDENT) {
            instruction(t);
        } else {
            throw err(t, "expected a label, instruction or directive");
        }
    }

    private void directive(Token d) {
        data = true; // whatever bytes a directive emits are data
        switch (d.text().toLowerCase(Locale.ROOT)) {
            case ".byte" -> data(d, Kind.BYTE);
            case ".word" -> data(d, Kind.WORD);
            case ".res" -> reserve(d, false);
            case ".align" -> reserve(d, true);
            case ".include", ".incbin" -> file(d);
            case ".defsection" -> declareSection(d);
            case ".section" -> switchSection();
            default -> throw err(d, "unknown directive '" + d.text() + "'");
        }
    }

    /** {@code .defsection NAME, ORIGIN[, reserved]}: declares a Section, without switching to it. */
    private void declareSection(Token d) {
        Token name = sectionName();
        if (sections.containsKey(name.text())) throw err(name, "Section " + name.text() + " is already declared");
        expect(",");
        int origin = origin();
        boolean reserved = peek().is(",");
        if (reserved) {
            pos++;
            Token flag = next();
            if (!flag.text().equalsIgnoreCase("reserved")) throw err(flag, "expected 'reserved'");
        }
        expectEnd();
        sections.put(name.text(), new Section(name.text(), origin, reserved, loc.at(d.col())));
    }

    /** {@code .section NAME}: continues a declared Section where it left off. */
    private void switchSection() {
        Token name = sectionName();
        expectEnd();
        Section s = sections.get(name.text());
        if (s == null) throw err(name, "Section " + name.text() + " is not declared: declare it with .defsection first");
        section = s;
    }

    private int origin() {
        Token at = peek();
        int origin = resolved(expr());
        if (origin < 0 || origin > 0xFFFF) throw err(at, "origin " + show(origin) + " is outside $0000-$FFFF");
        return origin;
    }

    private Token sectionName() {
        Token t = next();
        if (t.type() != IDENT || t.text().startsWith("@")) throw err(t, "expected a Section name");
        return t;
    }

    /** {@code .include "f"} or {@code .incbin "f"}, the path relative to the including file. */
    private void file(Token d) {
        boolean bin = d.text().equalsIgnoreCase(".incbin");
        if (bin) requireLoaded(d, ".incbin");
        Token name = next();
        if (name.type() != STRING) throw err(name, d.text().toLowerCase(Locale.ROOT) + " needs a \"file\" name");
        expectEnd();
        Path path = loc.file().resolveSibling(new String(name.bytes(), UTF_8)).normalize();
        if (!bin && including.contains(path)) throw err(name, "recursive .include of " + path);
        byte[] data;
        try {
            data = reader.read(path);
        } catch (IOException e) {
            throw err(name, "cannot read " + path + ": " + e);
        }
        if (bin) {
            for (byte b : data) emit(b & 0xFF);
            sized = true;
        } else {
            include = path;
            includeText = new String(data, UTF_8);
        }
    }

    /** {@code .res n[,fill]} or {@code .align n[,fill]}: n and fill must be resolved now. */
    private void reserve(Token d, boolean align) {
        requireSection(d);
        Token at = peek();
        int n = resolved(expr());
        int fill = 0;
        if (peek().is(",")) {
            pos++;
            Token f = peek();
            requireLoaded(f, "a fill");
            fill = bytes(Kind.BYTE, resolved(expr()), 0, loc.at(f.col()))[0];
        }
        expectEnd();
        if (align) {
            if (n <= 0) throw err(at, ".align boundary " + n + " is not positive");
            n = Math.floorMod(-section.pc(), n);
        } else if (n < 0) {
            throw err(at, ".res count " + n + " is negative");
        }
        int end = section.reserved && section.origin < 0x100 ? 0x100 : 0x10000; // reserved zero page mustn't reach the stack
        if (n > end - section.pc())
            throw err(at, (section.reserved ? "Reserved Section " + section.name : "code") + " runs past " + show(end - 1));
        for (int i = 0; i < n; i++) emit(fill);
        sized = true;
    }

    private void data(Token d, Kind kind) {
        requireLoaded(d, d.text().toLowerCase(Locale.ROOT));
        for (;;) {
            Token at = peek();
            if (kind == Kind.BYTE && at.type() == STRING && (peek(1).is(",") || peek(1).type() == END)) {
                pos++;
                for (byte b : at.bytes()) emit(b & 0xFF);
            } else {
                field(kind, expr(), at);
            }
            if (!peek().is(",")) break;
            pos++;
        }
        expectEnd();
    }

    private void instruction(Token m) {
        String name = m.text().toUpperCase(Locale.ROOT);
        Map<Mode, Integer> modes = OPCODES.get(name);
        if (modes == null) throw err(m, "unknown instruction '" + m.text() + "'");
        requireLoaded(m, "instructions");
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
                    : modes.containsKey(zp) && (fitsZp || (v.resolved() && !modes.containsKey(abs))) ? zp : abs;
            encode(name, modes, mode, v, at);
            if (!v.resolved() && forced == 0 && modes.containsKey(zp)) guessedAbsolute.add(fixups.getLast());
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
                        || (a.is(",") && isRegister(toks.get(i + 2), "Y") && toks.get(i + 3).type() == END);
            }
        }
        return false;
    }

    private void encode(String name, Map<Mode, Integer> modes, Mode mode, Value v, Token at) {
        Integer op = modes.get(mode);
        if (op == null && mode == IND) throw err(at, name + " has no (indirect) mode; to group an expression write 0+(...)");
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
            case IZX -> "(indirect,X)";
            case IZY -> "(indirect),Y";
            case IMP, ACC, IND, REL -> mode.name();
        };
    }

    // ---- expressions

    /** Binary operators, lowest precedence first; all left-associative. */
    private static final List<List<String>> LEVELS = List.of(
            List.of("|"), List.of("^"), List.of("&"), List.of("<<", ">>"), List.of("+", "-"), List.of("*", "/", "%"));

    /** An operand expression: a leading {@code <} or {@code >} takes the low or high byte of all of it. */
    private Value expr() {
        if (peek().is("<") || peek().is(">")) {
            boolean lo = next().is("<");
            Value v = binary(0);
            if (!v.resolved()) return new Value(v.symbol(), v.offset(), lo ? Kind.LO : Kind.HI, false);
            return Value.of(lo ? v.offset() & 0xFF : v.offset() >> 8 & 0xFF);
        }
        return binary(0);
    }

    private Value binary(int level) {
        if (level == LEVELS.size()) return unary();
        Value v = binary(level + 1);
        while (peek().type() == PUNCT && LEVELS.get(level).contains(peek().text())) {
            Token op = next();
            v = apply(op, v, binary(level + 1));
        }
        return v;
    }

    private Value apply(Token op, Value a, Value b) {
        if (a.resolved() && b.resolved()) {
            int x = a.offset(), y = b.offset();
            if ((op.is("/") || op.is("%")) && y == 0) throw err(op, "division by zero");
            int v = switch (op.text()) {
                case "|" -> x | y;
                case "^" -> x ^ y;
                case "&" -> x & y;
                case "<<" -> x << y;
                case ">>" -> x >> y;
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> x / y;
                case "%" -> x % y;
                default -> throw new AssertionError(op);
            };
            return new Value(null, v, a.address() || b.address());
        }
        // unresolved: only sym + const, sym - const, const + sym
        Value other = a.resolved() ? a : b;
        if (!other.resolved() || other.address() || !(op.is("+") || (op.is("-") && !a.resolved())))
            throw err(op, "define " + (a.resolved() ? b : a).symbol() + " before this use");
        return new Value(a.resolved() ? b.symbol() : a.symbol(), op.is("+") ? a.offset() + b.offset() : a.offset() - b.offset());
    }

    private Value unary() {
        if (peek().is("-") || peek().is("~")) {
            Token op = next();
            Value v = unary();
            if (!v.resolved()) throw err(op, "define " + v.symbol() + " before this use");
            return new Value(null, op.is("-") ? -v.offset() : ~v.offset(), v.address());
        }
        return term();
    }

    private Value term() {
        Token t = next();
        if (t.type() == NUMBER) return Value.of(t.value());
        if (t.type() == IDENT) {
            Symbol s = symbols.get(name(t));
            return s != null ? new Value(null, s.value(), s.label()) : new Value(name(t), 0);
        }
        if (t.is("*")) {
            requireSection(t);
            return new Value(null, section.pc(), true);
        }
        if (t.is("(")) {
            Value v = binary(0);
            expect(")");
            return v;
        }
        if (t.is("<") || t.is(">")) throw err(t, "'<' and '>' may only start an expression");
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
        if (v.part() != null) {
            // an unresolved low/high byte fills a byte field, or a word field's low byte (its high byte stays 0)
            if (kind == Kind.REL8) throw err(at, "define " + v.symbol() + " before this use");
            kind = v.part();
        }
        if (v.resolved()) {
            for (int b : bytes(kind, v.offset(), next, loc.at(at.col()))) emit(b);
        } else {
            fixups.add(new Fixup(section, section.bytes.size(), v.symbol(), v.offset(), kind, loc.at(at.col())));
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
        String n = name(name);
        if (symbols.containsKey(n)) throw err(name, name.text() + " is already defined");
        symbols.put(n, new Symbol(n, value, label, Linkage.LOCAL));
    }

    /** The symbol table name: a cheap local label {@code @x} is {@code parent@x}. */
    private String name(Token t) {
        if (!t.text().startsWith("@")) return t.text();
        String scope = scopes.get(section);
        if (scope == null) throw err(t, "cheap local label " + t.text() + " needs an ordinary label before it in this Section and file");
        return scope + t.text();
    }

    private void requireSection(Token t) {
        if (section == null) throw err(t, "no Section yet: switch to one with .section first");
    }

    /** {@code what} puts bytes in the image, so it needs a Section that isn't reserved. */
    private void requireLoaded(Token t, String what) {
        requireSection(t);
        if (section.reserved) throw err(t, what + " can't be in Reserved Section " + section.name + ": it puts no bytes in the image");
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
        for (Section s : sections.values()) data.put(s, s.bytes.toByteArray());
        for (Fixup f : fixups) {
            Symbol sym = symbols.get(f.symbol());
            if (sym == null) {
                int at = f.symbol().indexOf('@');
                errors.add(f.loc().error(at < 0 ? "undefined symbol " + f.symbol()
                        : "undefined symbol " + f.symbol().substring(at) + " in the scope of " + f.symbol().substring(0, at)));
                continue;
            }
            int size = f.kind() == Kind.WORD ? 2 : 1;
            byte[] b = data.get(f.section());
            try {
                int[] v = bytes(f.kind(), sym.value() + f.offset(), f.section().origin + f.at() + size, f.loc());
                for (int i = 0; i < v.length; i++) b[f.at() + i] = (byte) v[i];
                if (guessedAbsolute.contains(f) && b[f.at() + 1] == 0) // always so for <sym and >sym
                    errors.add(f.loc().error("operand " + show(v[0]) + " is in zero page: define " + f.symbol()
                            + " before this use, or write z: or a:"));
            } catch (AsmException e) {
                errors.add(e.loc.error(e.getMessage()));
            }
        }
        var placed = sections.values().stream().filter(s -> s.bytes.size() > 0).sorted(Comparator.comparingInt(s -> s.origin)).toList();
        Section last = null; // the placed Section reaching highest so far
        for (Section s : placed) {
            if (last != null && s.origin < last.pc())
                errors.add(s.loc.error(s.describe() + " overlaps " + last.describe()));
            if (last == null || s.pc() > last.pc()) last = s;
        }
        if (!errors.isEmpty()) return new Errors(List.copyOf(errors));

        var loaded = placed.stream().filter(s -> !s.reserved).toList(); // reserved ones only took part in the overlap check
        int lo = 0x10000, hi = 0;
        for (Section s : loaded) {
            lo = Math.min(lo, s.origin);
            hi = Math.max(hi, s.pc());
        }
        if (lo > hi) lo = hi = sections.values().stream().filter(s -> !s.reserved).findFirst().map(s -> s.origin).orElse(0);
        byte[] image = new byte[hi - lo];
        for (Section s : loaded) System.arraycopy(data.get(s), 0, image, s.origin - lo, s.bytes.size());
        return new Output(lo, image, listing(data), symbolFile(lo, image));
    }

    /** {@code ADDR  B1 B2 B3  SOURCE}; data over 3 bytes continues on lines without source. */
    private String listing(Map<Section, byte[]> data) {
        var out = new StringBuilder();
        Path file = null;
        for (Line l : lines) {
            if (file != null && !l.file().equals(file)) out.append("; file: ").append(l.file()).append('\n');
            file = l.file();
            if (l.sized()) {
                out.append(String.format("%04X  %-8s  %s", l.addr(), "(" + l.length() + " bytes)", l.text()).stripTrailing()).append('\n');
                continue;
            }
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

    /**
     * {@code image <hex origin> <hex Entry point> <hex CRC-32>} unless the image has no bytes (ADR 0003); then
     * {@code <hex value> <name> <label|const> <global|local>}, sorted by value, ties in definition order; then
     * {@code data <hex start> <hex length>} per run of loaded bytes from data directives, adjacent runs merged.
     */
    private String symbolFile(int origin, byte[] image) {
        var out = new StringBuilder();
        if (image.length > 0) {
            var sum = new CRC32();
            sum.update(image);
            out.append(String.format("image %04X %04X %08X\n", origin, origin, sum.getValue()));
        }
        symbols.values().stream().sorted(Comparator.comparingInt(Symbol::value)).forEach(s -> out.append(String.format(
                "%04X %s %s %s\n", s.value(), s.name(), s.label() ? "label" : "const",
                s.linkage().name().toLowerCase(Locale.ROOT))));
        int start = 0, end = -1; // the run being merged; none while end < 0
        var dataLines = lines.stream()
                .filter(l -> l.data() && l.length() > 0 && !l.section().reserved) // else code loaded into a buffer shows as .byte
                .sorted(Comparator.comparingInt(Line::addr))
                .toList();
        for (Line l : dataLines) {
            if (l.addr() == end) {
                end += l.length();
                continue;
            }
            if (end >= 0) out.append(String.format("data %04X %04X\n", start, end - start));
            start = l.addr();
            end = start + l.length();
        }
        if (end >= 0) out.append(String.format("data %04X %04X\n", start, end - start));
        return out.toString();
    }
}
