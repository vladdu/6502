package cpu;

/** Thrown by {@link Cpu#step()} when an interrupt entry (BRK, IRQ, NMI) finds $0000 in its vector; no state changed. */
public final class StrayInterrupt extends RuntimeException {
    public final int vector;

    public StrayInterrupt(int vector) {
        super(String.format("stray interrupt: vector $%04X = $0000", vector));
        this.vector = vector;
    }
}
