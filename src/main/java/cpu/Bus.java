package cpu;

/** What the CPU sees. Addresses are masked to 16 bits, values to 8. */
public interface Bus {
    int read(int a);

    void write(int a, int v);

    /** Side-effect-free read, for the Monitor and Disassembler. */
    int peek(int a);
}
