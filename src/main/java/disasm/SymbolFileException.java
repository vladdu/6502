package disasm;

/** A malformed symbol file line; the message is {@code file:line: error: msg}. */
public final class SymbolFileException extends Exception {
    public SymbolFileException(String message) {
        super(message);
    }
}
