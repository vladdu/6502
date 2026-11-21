package machine;

import cpu.Bus;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.IntPredicate;

/** A Disk of 256-byte Sectors in a raw host image, moved to and from RAM pages by DMA. */
public final class Disk implements Device {
    /** Cycles from the Step after a COMMAND write to completion, for every command. */
    static final int DELAY = 256;
    static final int READ = 0x01, WRITE = 0x02;
    static final int DONE = 0x80, BUSY = 0x40;

    private final Bus memory;
    private final IntPredicate ramPage;
    /** Null with no media. */
    private FileChannel image;
    private Path path;
    private int sectors;
    private boolean readOnly;
    private int control, error, sector, page, flags;
    private int command, latchedSector, latchedPage;
    private int elapsed;
    private boolean issuedThisStep;

    /** {@code memory} is the DMA handle; {@code ramPage} is the memory map's answer to which pages DMA may use. */
    Disk(Bus memory, IntPredicate ramPage) {
        this.memory = memory;
        this.ramPage = ramPage;
    }

    /**
     * Attaches a raw image file: Sector N at byte N*256, 1 to 65536 Sectors. A file the host won't open for
     * writing mounts read-only; check {@link #readOnly()}.
     */
    public void attach(Path file, boolean readOnly) throws IOException, LoadException {
        FileChannel channel;
        if (readOnly) channel = FileChannel.open(file, StandardOpenOption.READ);
        else try {
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (AccessDeniedException e) {
            channel = FileChannel.open(file, StandardOpenOption.READ);
            readOnly = true;
        }
        long size = channel.size();
        String bad = size == 0 ? "is empty"
                : size % 256 != 0 ? String.format("size %d is not a multiple of 256", size)
                : size > 256L * 0x10000 ? String.format("size %d is larger than 16 MiB", size)
                : null;
        if (bad != null) {
            channel.close();
            throw new LoadException("disk image " + bad);
        }
        image = channel;
        path = file;
        sectors = (int) (size / 256);
        this.readOnly = readOnly;
    }

    /** True when the attached image takes no WRITE (asked for, or the host refused write access). */
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public String describe() {
        return path == null ? "Disk: no media" : String.format("Disk: %s, %d bytes, %s, %s", path, sectors * 256,
                readOnly ? "read-only" : "read-write", (flags & BUSY) != 0 ? "busy" : "idle");
    }

    @Override
    public int read(int off) {
        int last = image == null ? 0 : sectors - 1;
        return switch (off) {
            case 0x01 -> flags | (error != 0 ? 0x20 : 0) | (image == null ? 0x10 : 0) | (readOnly ? 0x08 : 0);
            case 0x02 -> control;
            case 0x03 -> error;
            case 0x04 -> sector & 0xFF;
            case 0x05 -> sector >> 8;
            case 0x06 -> page;
            case 0x08 -> last & 0xFF;
            case 0x09 -> last >> 8;
            default -> 0xFF;
        };
    }

    @Override
    public int peek(int off) { return read(off); }

    @Override
    public void write(int off, int v) {
        switch (off) {
            case 0x00 -> {
                if ((flags & BUSY) != 0) return;
                command = v;
                latchedSector = sector;
                latchedPage = page;
                flags = BUSY;
                elapsed = 0;
                issuedThisStep = true;
            }
            case 0x01 -> flags &= ~DONE;
            case 0x02 -> control = v & 0x80;
            case 0x04 -> sector = (sector & 0xFF00) | v;
            case 0x05 -> sector = (sector & 0x00FF) | v << 8;
            case 0x06 -> page = v;
            default -> {}
        }
    }

    @Override
    public void tick(int cycles) {
        if ((flags & BUSY) == 0) return;
        if (issuedThisStep) {
            issuedThisStep = false; // the issuing Step's cycles don't count
            return;
        }
        elapsed += cycles;
        if (elapsed >= DELAY) complete();
    }

    @Override
    public boolean irq() {
        return (flags & DONE) != 0 && control != 0;
    }

    /** Clears the registers and aborts an in-flight command with nothing transferred; the media stays. */
    @Override
    public void reset() {
        control = error = sector = page = flags = 0;
    }

    private void complete() {
        error = transfer();
        flags = DONE;
    }

    /** Validates the latched command, then moves the Sector; returns the ERROR code. */
    private int transfer() {
        if (image == null) return 1;
        if (command != READ && command != WRITE) return 5;
        if (latchedSector >= sectors) return 2;
        if (!ramPage.test(latchedPage)) return 3;
        if (command == WRITE && readOnly) return 4;
        var buf = ByteBuffer.allocate(256);
        long at = (long) latchedSector * 256;
        int base = latchedPage << 8;
        try {
            if (command == READ) {
                while (buf.hasRemaining()) if (image.read(buf, at + buf.position()) < 0) return 6;
                for (int i = 0; i < 256; i++) memory.write(base | i, buf.get(i));
            } else {
                for (int i = 0; i < 256; i++) buf.put(i, (byte) memory.read(base | i));
                image.write(buf, at);
            }
        } catch (IOException e) {
            return 6;
        }
        return 0;
    }
}
