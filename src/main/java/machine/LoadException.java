package machine;

/** An image that can't be placed in the Machine's memory map. */
public final class LoadException extends Exception {
    public LoadException(String message) {
        super(message);
    }
}
