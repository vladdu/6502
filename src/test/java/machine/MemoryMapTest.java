package machine;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void theAreasListTheWholeMemoryMapInAddressOrderWithUnmappedGapsAndRomImages() throws Exception {
        var m = new Machine();
        m.attachRom(m.systemRom, new byte[0x100]);
        m.map(0xD600, 0x200, new Device() {
            @Override public int read(int off) { return 0; }
            @Override public void write(int off, int v) {}
            @Override public int peek(int off) { return 0; }
        });

        assertEquals(List.of(
                new Machine.Area(0x0000, 0xC800, Machine.Area.Kind.RAM, "RAM", false),
                new Machine.Area(0xC800, 0x0800, Machine.Area.Kind.ROM, "Character ROM", false),
                new Machine.Area(0xD000, 0x0100, Machine.Area.Kind.DEVICE, "Console", false),
                new Machine.Area(0xD100, 0x0100, Machine.Area.Kind.DEVICE, "Timer", false),
                new Machine.Area(0xD200, 0x0100, Machine.Area.Kind.DEVICE, "Disk", false),
                new Machine.Area(0xD300, 0x0100, Machine.Area.Kind.DEVICE, "Exit device", false),
                new Machine.Area(0xD400, 0x0200, Machine.Area.Kind.UNMAPPED, "", false),
                new Machine.Area(0xD600, 0x0200, Machine.Area.Kind.DEVICE, "", false),
                new Machine.Area(0xD800, 0x0800, Machine.Area.Kind.UNMAPPED, "", false),
                new Machine.Area(0xE000, 0x2000, Machine.Area.Kind.ROM, "system ROM", true)), m.areas());
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
