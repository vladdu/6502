package gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HistoryTest {
    private final History h = new History();

    @Test
    void upWalksBackToTheOldestAndDownForwardPastTheNewestToAnEmptyLine() {
        h.add("r");
        h.add("m 0300");

        assertEquals("m 0300", h.previous());
        assertEquals("r", h.previous());
        assertEquals("r", h.previous());
        assertEquals("m 0300", h.next());
        assertEquals("", h.next());
        assertEquals("", h.next());
    }

    @Test
    void addingALineStartsAgainFromTheNewestAndBlankLinesAreNotKept() {
        h.add("r");
        h.previous();
        h.add("");
        h.add("d");

        assertEquals("d", h.previous());
        assertEquals("r", h.previous());
    }

    @Test
    void anEmptyHistoryGivesAnEmptyLine() {
        assertEquals("", h.previous());
        assertEquals("", h.next());
    }
}
