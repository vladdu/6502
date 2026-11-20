package asm;

/** An error at a source position; the pass records it and moves to the next line. */
final class AsmException extends RuntimeException {
    final Loc loc;

    AsmException(Loc loc, String msg) {
        super(msg, null, false, false);
        this.loc = loc;
    }
}
