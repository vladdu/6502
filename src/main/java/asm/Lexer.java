package asm;

import java.util.ArrayList;
import java.util.List;

/** Splits one source line into tokens, dropping the comment. */
final class Lexer {
    enum Type { IDENT, DIRECTIVE, NUMBER, PUNCT, END }

    record Token(Type type, String text, int value, int col) {
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
            } else if (identStart(c) || c == '.' && i + 1 < s.length() && identStart(s.charAt(i + 1))) {
                i++;
                while (i < s.length() && identPart(s.charAt(i))) i++;
                out.add(new Token(c == '.' ? Type.DIRECTIVE : Type.IDENT, s.substring(start, i), 0, start + 1));
            } else if (c == '$' || c == '%' || Character.isDigit(c)) {
                int radix = c == '$' ? 16 : c == '%' ? 2 : 10;
                if (radix != 10) i++;
                int digits = i;
                while (i < s.length() && Character.isLetterOrDigit(s.charAt(i))) i++;
                try {
                    long v = Long.parseLong(s.substring(digits, i), radix);
                    if (v > 0xFFFFFFFFL) throw new NumberFormatException();
                    out.add(new Token(Type.NUMBER, s.substring(start, i), (int) v, start + 1));
                } catch (NumberFormatException e) {
                    throw new AsmException(loc.at(start + 1), "bad number '" + s.substring(start, i) + "'");
                }
            } else if (c == '\'') {
                // 'A': exactly one ASCII character, so exactly one byte
                if (i + 2 >= s.length() || s.charAt(i + 2) != '\'' || s.charAt(i + 1) >= 0x80)
                    throw new AsmException(loc.at(start + 1), "character literal must be one byte, like 'A'");
                out.add(new Token(Type.NUMBER, s.substring(i, i + 3), s.charAt(i + 1), start + 1));
                i += 3;
            } else if ("+-()#,*=:".indexOf(c) >= 0) {
                out.add(new Token(Type.PUNCT, String.valueOf(c), 0, start + 1));
                i++;
            } else {
                throw new AsmException(loc.at(start + 1), "unexpected character '" + c + "'");
            }
        }
        out.add(new Token(Type.END, "", 0, i + 1));
        return out;
    }

    private static boolean identStart(char c) {
        return c == '_' || c < 0x80 && Character.isLetter(c);
    }

    private static boolean identPart(char c) {
        return identStart(c) || c >= '0' && c <= '9';
    }
}
