package disasm;

/** An unreadable symbol file, or a malformed line ({@code file:line: error: msg}); the message is complete. */
public final class SymbolFileException extends Exception {
    public SymbolFileException(String message) {
        super(message);
    }
}
