package asm;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Splits one source line into tokens, dropping the comment. */
final class Lexer {
    enum Type { IDENT, DIRECTIVE, NUMBER, STRING, PUNCT, END }

    /** {@code bytes} holds a STRING's decoded bytes, else null. */
    @SuppressWarnings("ArrayRecordComponent") // never compared with equals
    record Token(Type type, String text, int value, int col, byte[] bytes) {
        Token(Type type, String text, int value, int col) {
            this(type, text, value, col, null);
        }

        boolean is(String punct) {
            return type == Type.PUNCT && text.equals(punct);
        }
    }

    private Lexer() {}

    static List<Token> lex(Loc loc) {
        String s = loc.text();
        var out = new ArrayList<Token>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int start = i;
            if (c == ';') break;
            if (c == ' ' || c == '\t') {
                i++;
            } else if (identStart(c) || ((c == '.' || c == '@') && i + 1 < s.length() && identStart(s.charAt(i + 1)))) {
                i++;
                while (i < s.length() && identPart(s.charAt(i))) i++;
                out.add(new Token(c == '.' ? Type.DIRECTIVE : Type.IDENT, s.substring(start, i), 0, start + 1));
            } else if (c == '$' || (c == '%' && !afterValue(out)) || Character.isDigit(c)) {
                int radix = c == '$' ? 16 : c == '%' ? 2 : 10;
                if (radix != 10) i++;
                int digits = i;
                while (i < s.length() && Character.isLetterOrDigit(s.charAt(i))) i++;
                try {
                    long v = Long.parseLong(s.substring(digits, i), radix);
                    if (v > 0xFFFFFFFFL) throw new NumberFormatException();
                    out.add(new Token(Type.NUMBER, s.substring(start, i), (int) v, start + 1));
                } catch (NumberFormatException _) {
                    throw new AsmException(loc.at(start + 1), "bad number '" + s.substring(start, i) + "'");
                }
            } else if (c == '\'') {
                // 'A': exactly one ASCII character, so exactly one byte
                if (i + 2 >= s.length() || s.charAt(i + 2) != '\'' || s.charAt(i + 1) >= 0x80)
                    throw new AsmException(loc.at(start + 1), "character literal must be one byte, like 'A'");
                out.add(new Token(Type.NUMBER, s.substring(i, i + 3), s.charAt(i + 1), start + 1));
                i += 3;
            } else if (c == '"') {
                var b = new ByteArrayOutputStream();
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\') {
                        i = escape(loc, s, i, b);
                    } else {
                        int cp = s.codePointAt(i);
                        b.writeBytes(Character.toString(cp).getBytes(UTF_8));
                        i += Character.charCount(cp);
                    }
                }
                if (i == s.length()) throw new AsmException(loc.at(start + 1), "unterminated string");
                i++;
                out.add(new Token(Type.STRING, s.substring(start, i), 0, start + 1, b.toByteArray()));
            } else if ((c == '<' || c == '>') && i + 1 < s.length() && s.charAt(i + 1) == c) {
                out.add(new Token(Type.PUNCT, s.substring(i, i + 2), 0, start + 1));
                i += 2;
            } else if ("+-()#,*=:~/%&^|<>".indexOf(c) >= 0) {
                out.add(new Token(Type.PUNCT, String.valueOf(c), 0, start + 1));
                i++;
            } else {
                throw new AsmException(loc.at(start + 1), "unexpected character '" + c + "'");
            }
        }
        out.add(new Token(Type.END, "", 0, i + 1));
        return out;
    }

    /** Writes the escape at {@code s[i]} (a backslash) to {@code b}; returns the index after it. */
    private static int escape(Loc loc, String s, int i, ByteArrayOutputStream b) {
        char e = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
        switch (e) {
            case 'n' -> b.write('\n');
            case 'r' -> b.write('\r');
            case 't' -> b.write('\t');
            case '0' -> b.write(0);
            case '\\', '"', '\'' -> b.write(e);
            case 'x' -> {
                if (i + 4 > s.length() || !HexFormat.isHexDigit(s.charAt(i + 2)) || !HexFormat.isHexDigit(s.charAt(i + 3)))
                    throw new AsmException(loc.at(i + 1), "\\x needs two hex digits");
                b.write(HexFormat.fromHexDigits(s, i + 2, i + 4));
                return i + 4;
            }
            default -> throw new AsmException(loc.at(i + 1), "unknown escape '\\" + e + "'");
        }
        return i + 2;
    }

    /** Whether the tokens so far end a value, so a '%' next is modulo rather than a binary prefix. */
    private static boolean afterValue(List<Token> out) {
        int n = out.size() - 1;
        if (n < 0) return false;
        Token t = out.get(n);
        return switch (t.type()) {
            case NUMBER, STRING -> true;
            // the first word of a statement (after an optional label) is a mnemonic, not a value
            case IDENT -> n != 0 && !(n == 2 && out.get(1).is(":"));
            // '*' after a value is multiply; otherwise it reads the PC
            case PUNCT -> t.is(")") || (t.is("*") && !afterValue(out.subList(0, n)));
            case DIRECTIVE, END -> false;
        };
    }

    private static boolean identStart(char c) {
        return c == '_' || (c < 0x80 && Character.isLetter(c));
    }

    private static boolean identPart(char c) {
        return identStart(c) || (c >= '0' && c <= '9');
    }
}
