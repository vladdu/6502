package machine;

/** A piece of Machine hardware on the memory map. Offsets are relative to the Device's base. */
public interface Device {
    int read(int off);

    void write(int off, int v);

    /** Side-effect-free read. No default: every Device decides what its reads cost. */
    int peek(int off);

    /** Hardware Reset of the Machine. */
    default void reset() {}

    /** Called after every Step with that Step's cycles. */
    default void tick(int cycles) {}

    /** IRQ output level; the Machine wired-ORs all Devices onto the CPU pin. */
    default boolean irq() { return false; }

    /** NMI output level; the CPU detects the rising edge. */
    default boolean nmi() { return false; }
}
