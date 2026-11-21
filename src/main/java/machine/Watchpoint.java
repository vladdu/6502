package machine;

/** A Watchpoint over {@code [start, start + length)}, hit by Bus reads, writes or both inside a Step. */
public record Watchpoint(int start, int length, boolean reads, boolean writes) {
    /** Whether {@code address} is in the range. */
    public boolean covers(int address) {
        return address >= start && address < start + length;
    }

    /** Whether a read, or a write, at {@code address} hits it. */
    public boolean matches(int address, boolean write) {
        return covers(address) && (write ? writes : reads);
    }

    /** The range as the Monitor lists it: {@code $0300}, or {@code $0300-$0303}. */
    public String range() {
        int end = start + length - 1;
        return String.format("$%04X", start) + (end == start ? "" : String.format("-$%04X", end));
    }

    /** {@code r}, {@code w} or {@code rw}. */
    public String kind() {
        return reads ? writes ? "rw" : "r" : "w";
    }
}
