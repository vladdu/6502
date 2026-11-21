package machine;

/**
 * A ROM area, fixed in place and size by the memory map: RAM until an image is attached, then read-only over its
 * whole size, bytes the image doesn't cover reading $FF. It implements {@link Device} only so the bus reaches it like
 * any mapping.
 */
public final class Rom implements Device {
    final int base, size;
    /** Where a short image sits: against the area's end (the system ROM, so the vectors land at $FFFA) or its start. */
    private final boolean endAligned;
    /** Null until attached. */
    private byte[] image;
    /** Area offset of the image's first byte. */
    private int start;
    /** The RAM the area is until an image is attached. */
    private final Device ram;

    Rom(int base, int size, boolean endAligned, Device ram) {
        this.base = base;
        this.size = size;
        this.endAligned = endAligned;
        this.ram = ram;
    }

    boolean attached() {
        return image != null;
    }

    void attach(byte[] image) {
        this.image = image.clone();
        start = endAligned ? size - image.length : 0;
    }

    /** Whether {@code addr} holds a byte of the attached image. */
    boolean holds(int addr) {
        int i = addr - base - start;
        return image != null && i >= 0 && i < image.length;
    }

    public int read(int off) {
        if (image == null) return ram.read(off);
        int i = off - start;
        return i >= 0 && i < image.length ? image[i] & 0xFF : 0xFF;
    }

    public void write(int off, int v) {
        if (image == null) ram.write(off, v);
    }

    public int peek(int off) { return read(off); }
}
