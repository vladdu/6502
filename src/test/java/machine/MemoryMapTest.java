package machine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whatever the memory map holds, load and Disk DMA accept exactly the pages the bus treats as RAM. */
class MemoryMapTest {
    @TempDir Path dir;

    private static Machine plain() {
        return new Machine();
    }

    /** Both ROM areas attached with short images, so each area also has bytes no image covers. */
    private static Machine withRoms() {
        var m = new Machine();
        try {
            m.attachRom(m.systemRom, new byte[0x100]);
            m.attachRom(m.charRom, new byte[0x10]);
        } catch (LoadException e) {
            throw new AssertionError(e);
        }
        return m;
    }

    @Test
    void loadAndDiskDmaAcceptExactlyTheRamPagesWithNoRomAttached() throws Exception {
        check(MemoryMapTest::plain);
    }

    @Test
    void loadAndDiskDmaAcceptExactlyTheRamPagesWithBothRomsAttached() throws Exception {
        check(MemoryMapTest::withRoms);
    }

    private void check(Supplier<Machine> machine) throws Exception {
        var probe = machine.get(); // writes everywhere, Devices included, so kept apart from m
        var m = machine.get();
        m.disk.attach(Files.write(dir.resolve("disk.img"), new byte[256]), false);
        for (int p = 0; p < 0x100; p++) {
            boolean ram = holdsWrites(probe, p);
            String at = String.format("page $%02X", p);
            m.write(0xD206, p); // PAGE
            m.write(0xD200, Disk.READ);
            m.disk.tick(Disk.DELAY);
            assertEquals(ram ? 0 : 3, m.bus.peek(0xD203), at + ": Disk READ error");
            m.write(0xD206, p);
            m.write(0xD200, Disk.WRITE);
            m.disk.tick(Disk.DELAY);
            assertEquals(ram ? 0 : 3, m.bus.peek(0xD203), at + ": Disk WRITE error");
            int page = p;
            if (ram) m.load(new byte[1], p << 8);
            else assertThrows(LoadException.class, () -> m.load(new byte[1], page << 8), at + ": load");
        }
    }

    /** Whether every byte of page {@code p} reads back what the bus wrote, for two patterns. */
    private static boolean holdsWrites(Machine m, int p) {
        for (int v : new int[] {0x55, 0xAA})
            for (int a = p << 8; a < (p + 1) << 8; a++) {
                m.bus.write(a, v);
                if (m.bus.peek(a) != v) return false;
            }
        return true;
    }
}
