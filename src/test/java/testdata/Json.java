package testdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for the SingleStepTests subset of JSON: objects, arrays, non-negative
 * integers and strings without escapes. Anything else is an {@link IllegalArgumentException}.
 * Values come back as {@code Map<String, Object>}, {@code List<Object>}, {@code Integer} and {@code String}.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        var p = new Json(text);
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.error("trailing content");
        return v;
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c >= '0' && c <= '9') return integer();
        throw error("unexpected '" + c + "'");
    }

    private Map<String, Object> object() {
        var m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw error("expected key");
            String k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (peek() == '}') { i++; return m; }
            expect(',');
        }
    }

    private List<Object> array() {
        var l = new ArrayList<Object>();
        i++;
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            if (peek() == ']') { i++; return l; }
            expect(',');
        }
    }

    private String string() {
        int start = ++i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') return s.substring(start, i++);
            if (c == '\\' || c < 0x20) throw error("escape or control character in string");
            i++;
        }
        throw error("unterminated string");
    }

    private Integer integer() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (s.charAt(start) == '0' && i - start > 1) throw error("leading zero");
        if (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) throw error("not an integer");
        try {
            return Integer.valueOf(s.substring(start, i));
        } catch (NumberFormatException _) {
            throw error("integer out of range");
        }
    }

    private void ws() {
        while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++;
    }

    private char peek() {
        if (i >= s.length()) throw error("unexpected end");
        return s.charAt(i);
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String why) {
        return new IllegalArgumentException(why + " at offset " + i);
    }
}
