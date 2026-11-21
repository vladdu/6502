package gui;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * The Console pane's text: Console output bytes in, edits to the shown text out. A plain text pane, no ANSI: the cursor only
 * moves within the last line, so earlier lines never change.
 */
final class ConsoleText {
    static final int MAX_LINES = 10_000;

    /** The pane's text after every write so far. */
    private final StringBuilder text = new StringBuilder();
    /** Where the last line starts, the cursor's offset in it, and the newlines in {@link #text}. */
    private int lineStart, column, newlines;
    private final CharsetDecoder utf8 = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE);
    /** The start of a character the last write split. */
    private ByteBuffer partial = ByteBuffer.allocate(0);
    /** Since the last {@link #take}: the lowest index written at, the chars trimmed off the front, and the bells rung. */
    private int from, trim, bells;
    /** The length of the pane's text at the last {@link #take}. */
    private int shown;

    /** A change to the shown text: drop its first {@code trim} chars, then replace from {@code from} to the end with {@code tail}. */
    record Edit(int trim, int from, String tail, int bells) {}

    /** Takes the next Console output bytes, decoded as UTF-8 incrementally. */
    void write(byte[] bytes) {
        ByteBuffer in = ByteBuffer.allocate(partial.remaining() + bytes.length).put(partial).put(bytes).flip();
        CharBuffer out = CharBuffer.allocate(in.remaining()); // UTF-8 never decodes to more chars than bytes
        utf8.decode(in, out, false);
        partial = in;
        out.flip().codePoints().forEach(this::put);
        if (newlines < MAX_LINES) return;
        int cut = 0; // once per write: trimming copies the whole text
        for (int n = newlines - MAX_LINES + 1; n > 0; n--) cut = text.indexOf("\n", cut) + 1;
        text.delete(0, cut);
        newlines = MAX_LINES - 1;
        lineStart -= cut;
        from = Math.max(0, from - cut);
        trim += cut;
    }

    private void put(int c) {
        switch (c) {
            case '\n' -> {
                text.append('\n');
                newlines++;
                lineStart = text.length();
                column = 0;
            }
            case '\r' -> column = 0;
            case '\b' -> {
                if (column > 0) column = text.offsetByCodePoints(lineStart + column, -1) - lineStart;
            }
            case 0x07 -> bells++;
            default -> {
                if (Character.isISOControl(c)) return;
                String s = Character.toString(c);
                int at = lineStart + column;
                text.replace(at, at < text.length() ? text.offsetByCodePoints(at, 1) : at, s);
                from = Math.min(from, at);
                column += s.length();
            }
        }
    }

    /** The edit that brings the shown text up to date, or null if nothing changed. */
    Edit take() {
        if (from == text.length() && bells == 0) return null;
        var e = new Edit(Math.min(trim, shown), from, text.substring(from), bells); // from 0 replaces it all: past shown is moot
        shown = from = text.length();
        trim = bells = 0;
        return e;
    }
}
