package cpu;

/** Plain 64 KiB RAM: the whole Bus for CPU tests. */
final class Ram implements Bus {
    final int[] mem = new int[0x10000];

    public int read(int a) { return mem[a & 0xFFFF]; }
    public void write(int a, int v) { mem[a & 0xFFFF] = v & 0xFF; }
    public int peek(int a) { return mem[a & 0xFFFF]; }

    void load(int addr, int... bytes) {
        for (int b : bytes) mem[addr++ & 0xFFFF] = b & 0xFF;
    }
}
