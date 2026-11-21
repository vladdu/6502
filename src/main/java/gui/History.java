package gui;

import java.util.ArrayList;
import java.util.List;

/** The command line's history for one session: Up walks back to the oldest line, Down forward past the newest to an empty line. */
final class History {
    private final List<String> lines = new ArrayList<>();
    /** The line shown; {@code lines.size()} is the empty new line. */
    private int at;

    /** Keeps a submitted line (not a blank one) and starts again from the newest. */
    void add(String line) {
        if (!line.isBlank()) lines.add(line);
        at = lines.size();
    }

    /** Up. */
    String previous() {
        if (at > 0) at--;
        return shown();
    }

    /** Down. */
    String next() {
        if (at < lines.size()) at++;
        return shown();
    }

    private String shown() {
        return at < lines.size() ? lines.get(at) : "";
    }
}
