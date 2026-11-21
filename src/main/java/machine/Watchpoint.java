package machine;

/** A Watchpoint over {@code [start, start + length)}, hit by Bus reads, writes or both inside a Step. */
public record Watchpoint(int start, int length, boolean reads, boolean writes) {}
