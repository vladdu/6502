package gui;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Typed key characters to Console bytes: the char as UTF-8, a surrogate pair as one character; Ctrl-] is the user break. */
final class ConsoleKeys {
    private static final byte[] NONE = {};
    /** A high surrogate waiting for its low half. */
    private char high;

    /** The Console bytes for one typed character; none for Ctrl-] and for keys with no character. */
    byte[] typed(char c) {
        if (c == '\u001D' || c == '￿') return NONE;
        if (Character.isHighSurrogate(c)) {
            high = c;
            return NONE;
        }
        String s = Character.isLowSurrogate(c) && high != 0 ? new String(new char[] {high, c}) : String.valueOf(c);
        high = 0;
        return s.getBytes(UTF_8);
    }
}
