package gui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConsoleTextTest {
    private final ConsoleText text = new ConsoleText();
    /** The pane's text, kept by applying every {@link ConsoleText.Edit} as the pane does. */
    private final StringBuilder shown = new StringBuilder();
    private int bells;

    /** Writes {@code bytes} as one drain and returns the shown text after the refresh tick. */
    private String drain(byte[] bytes) {
        text.write(bytes);
        ConsoleText.Edit e = text.take();
        if (e != null) {
            shown.delete(0, e.trim());
            shown.replace(e.from(), shown.length(), e.tail());
            bells += e.bells();
        }
        return shown.toString();
    }

    private String drain(String s) {
        return drain(s.getBytes(UTF_8));
    }

    @Test
    void carriageReturnGoesBackToTheLineStartAndOverwrites() {
        assertEquals("hello", drain("hello"));
        assertEquals("Jello", drain("\rJ"));
        assertEquals("XYllo\nZ", drain("\rXY\nZ"));
    }

    @Test
    void backspaceMovesBackOneWithoutErasingAndStopsAtTheLineStart() {
        assertEquals("aXc", drain("abc\b\bX"));
        assertEquals("aXc\nY", drain("\n\b\bY"));
        assertEquals("aXc\nYZ!", drain("\uD83D\uDE00!\b\bZ")); // back over, and over-write, a whole supplementary character
    }

    @Test
    void bellRingsWithoutChangingTheText() {
        assertEquals("ab", drain("a\u0007b\u0007"));
        assertEquals(2, bells);
        assertEquals("ab", drain("\u0007"));
        assertEquals(3, bells);
    }

    @Test
    void otherControlCharactersAreDroppedAndEscapeSequencesShowAsText() {
        assertEquals("abc[31mred", drain("a\tb\u0001\u007Fc\u001B[31m\u0085red"));
    }

    @Test
    void aCharacterSplitAcrossDrainsComesOutWholeAndMalformedBytesShowAsReplacement() {
        assertEquals("a", drain(new byte[] {'a', (byte) 0xC3}));
        assertEquals("a\u00E9", drain(new byte[] {(byte) 0xA9}));
        assertEquals("a\u00E9", drain(new byte[] {(byte) 0xF0, (byte) 0x9F}));
        assertEquals("a\u00E9\uD83D\uDE00", drain(new byte[] {(byte) 0x98, (byte) 0x80}));
        assertEquals("a\u00E9\uD83D\uDE00\uFFFDb", drain(new byte[] {(byte) 0xFF, 'b'}));
    }

    private static String lines(int from, int to) {
        var b = new StringBuilder();
        for (int i = from; i < to; i++) b.append("line ").append(i).append('\n');
        return b.toString();
    }

    @Test
    void scrollbackKeepsTheLast10000LinesDrainByDrain() {
        for (int i = 0; i < 10_005; i++) drain("line " + i + "\n");
        assertEquals(lines(6, 10_005), shown.toString()); // 9,999 lines and the empty last one
        assertEquals(lines(6, 10_005) + "tail", drain("tail"));
        assertEquals(lines(7, 10_005) + "tail\n", drain("\n"));
    }

    @Test
    void oneDrainOfMoreThanTheCapReplacesEverythingShown() {
        drain("abcdefghijkl\nxyz");
        assertEquals(lines(10_001, 20_000), drain("\r" + lines(0, 20_000)));
    }
}
