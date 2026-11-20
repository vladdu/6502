package machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MachineTest {
    private final Machine m = new Machine();

    private static byte[] bytes(int... b) {
        var out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    @Test
    void programModeLoadsAtOrgInAKnownState() throws LoadException {
        m.loadProgram(bytes(0xEA, 0xEA), 0x0300);

        assertEquals(0x0300, m.cpu.pc);
        assertEquals(0xFD, m.cpu.s);
        assertEquals(0x24, m.cpu.p); // I set, bit 5 set
        assertEquals(0, m.cpu.a); assertEquals(0, m.cpu.x); assertEquals(0, m.cpu.y);
        assertEquals(0xEA, m.bus.peek(0x0300));
        assertEquals(0x00, m.bus.peek(0x0000));
        assertEquals(0x00, m.bus.peek(0x02FF));
        assertEquals(0x00, m.bus.peek(0x9FFF));
    }

    @Test
    void writingTheExitDeviceExitsAfterTheWritingStep() throws LoadException {
        m.loadProgram(bytes(
                0xA2, 0x07,       // LDX #7       2
                0xE8,             // INX          2
                0xEA,             // NOP          2
                0xA9, 0x2A,       // LDA #42      2
                0x8D, 0x00, 0xD3, // STA $D300    4
                0xE8,             // INX (not reached)
                0xDB), 0x0200);

        assertEquals(new StopReason.Exited(42), m.run(Set.of()));
        assertEquals(0x0209, m.cpu.pc); // just past the STA
        assertEquals(0x08, m.cpu.x);
        assertEquals(12, m.cycles);
    }

    @Test
    void undocumentedOpcodeStopsWithIllegalAndNoStateChanged() throws LoadException {
        m.loadProgram(bytes(0xA9, 0x05, 0x02), 0x0200); // LDA #5; $02 (undocumented)

        assertEquals(new StopReason.Illegal(0x0202, 0x02), m.run(Set.of()));
        assertEquals(0x0202, m.cpu.pc);
        assertEquals(0x05, m.cpu.a);
        assertEquals(2, m.cycles);
    }

    @Test
    void loadOverlappingTheIoRegionIsALoadError() {
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[2], 0xCFFF));
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[1], 0xDFFF));
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[0x2000], 0xC000));
    }

    @Test
    void loadPastTheTopOfMemoryIsALoadError() {
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[2], 0xFFFF));
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[1], 0x10000));
    }

    @Test
    void romSpaceAndReservedVideoAreasAreRamWithNoImage() throws LoadException {
        m.loadProgram(bytes(0x11, 0x22), 0xFFFE);
        assertEquals(0x22, m.bus.read(0xFFFF));
        for (int a : new int[] {0xA000, 0xC000, 0xC400, 0xC7FF, 0xE000}) {
            m.bus.write(a, 0x5A);
            assertEquals(0x5A, m.bus.read(a), String.format("$%04X", a));
        }
    }

    @Test
    void unmappedAddressesReadFFAndIgnoreWrites() {
        for (int a : new int[] {0xC800, 0xCFFF, 0xD400, 0xD600, 0xDFFF, 0xD301, 0xD3FF}) {
            m.bus.write(a, 0x00);
            assertEquals(0xFF, m.bus.read(a), String.format("$%04X", a));
            assertEquals(0xFF, m.bus.peek(a), String.format("$%04X", a));
        }
    }

    @Test
    void exitDeviceReadsFF() {
        assertEquals(0xFF, m.bus.read(0xD300));
        assertEquals(0xFF, m.bus.peek(0xD300));
    }

    @Test
    void breakpointAtTheStartingPcDoesNotStopTheFirstStep() throws LoadException {
        m.loadProgram(bytes(0x4C, 0x00, 0x02), 0x0200); // JMP $0200

        assertEquals(new StopReason.Breakpoint(0x0200), m.run(Set.of(0x0200)));
        assertEquals(3, m.cycles);
    }

    @Test
    void breakRequestStopsBeforeTheNextStep() throws LoadException {
        m.loadProgram(bytes(0xEA), 0x0200);
        m.breakRequested = true;

        assertEquals(new StopReason.UserBreak(), m.run(Set.of()));
        assertEquals(0x0200, m.cpu.pc);
        assertFalse(m.breakRequested);
    }

    @Test
    void devicesAreTickedWithEachStepsCyclesAndDriveThePins() throws LoadException {
        var dev = new Device() {
            int ticked;
            public int read(int off) { return 0; }
            public void write(int off, int v) {}
            public int peek(int off) { return 0; }
            public void tick(int cycles) { ticked += cycles; }
            public boolean irq() { return ticked >= 6; }
        };
        m.map(0xD600, 0x100, dev);
        m.loadProgram(bytes(0x8D, 0x00, 0x02, 0xEA), 0x0200); // STA $0200; NOP

        m.step();
        assertEquals(4, dev.ticked);
        assertFalse(m.cpu.irqLine);
        m.step();
        assertEquals(6, dev.ticked);
        assertTrue(m.cpu.irqLine);
    }
}
