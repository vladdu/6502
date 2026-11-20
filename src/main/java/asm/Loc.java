package asm;

import java.nio.file.Path;

/** A source position, 1-based, with the line's text for the caret display. */
record Loc(Path file, int line, int col, String text) {
    Loc at(int col) {
        return new Loc(file, line, col, text);
    }

    /** {@code file:line:col: error: msg}, the source line, then a caret under col. */
    String error(String msg) {
        var caret = new StringBuilder();
        for (int i = 0; i < col - 1; i++) caret.append(i < text.length() && text.charAt(i) == '\t' ? '\t' : ' ');
        return file + ":" + line + ":" + col + ": error: " + msg + "\n" + text + "\n" + caret + "^";
    }
}
