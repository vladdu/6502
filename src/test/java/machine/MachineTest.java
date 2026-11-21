package machine;

import static org.junit.jupiter.api.Assertions.*;

import cpu.Cpu;
import cpu.Entry;
import java.util.List;
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
    void romAreasAndReservedVideoAreasAreRamWithNoImage() throws LoadException {
        m.loadProgram(bytes(0x11, 0x22), 0xFFFE);
        assertEquals(0x22, m.bus.read(0xFFFF));
        m.loadProgram(bytes(0x33), 0xC800);
        assertEquals(0x33, m.bus.read(0xC800));
        for (int a : new int[] {0xA000, 0xC000, 0xC400, 0xC7FF, 0xC800, 0xCFFF, 0xE000}) {
            m.bus.write(a, 0x5A);
            assertEquals(0x5A, m.bus.read(a), String.format("$%04X", a));
        }
    }

    @Test
    void unmappedAddressesReadFFAndIgnoreWrites() {
        for (int a : new int[] {0xD400, 0xD600, 0xDFFF, 0xD301, 0xD3FF}) {
            m.bus.write(a, 0x00);
            assertEquals(0xFF, m.bus.read(a), String.format("$%04X", a));
            assertEquals(0xFF, m.bus.peek(a), String.format("$%04X", a));
        }
    }

    @Test
    void exitDeviceReadsFFAndOnlyD300Exits() {
        assertEquals(0xFF, m.bus.read(0xD300));
        assertEquals(0xFF, m.bus.peek(0xD300));
        assertNull(m.write(0xD301, 7));
        assertNull(m.write(0xD3FF, 7));
        assertEquals(7, m.write(0xD300, 7));
    }

    @Test
    void breakpointAtTheStartingPcDoesNotStopTheFirstStep() throws LoadException {
        m.loadProgram(bytes(0x4C, 0x00, 0x02), 0x0200); // JMP $0200

        assertEquals(new StopReason.Breakpoint(0x0200), m.run(Set.of(0x0200)));
        assertEquals(3, m.cycles);
    }

    @Test
    void aStepLimitStopsAsSteppedWithTheLastStepsInterruptEntry() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        m.loadProgram(bytes(0x58, 0xEA), 0x0200); // CLI; NOP
        vector(0xFFFE, 0x0300);
        pins.irq = true; // on the pin after the CLI Step

        assertEquals(new StopReason.Stepped(null, 0x0201), m.run(Set.of(), 1));
        assertEquals(new StopReason.Stepped(Entry.IRQ, 0x0300), m.run(Set.of(), 1));
        assertEquals("IRQ entry -> $0300", new StopReason.Stepped(Entry.IRQ, 0x0300).toString());
        assertEquals(new StopReason.Stepped(null, 0x0300), m.run(Set.of(), 0)); // no Step run, so no entry
        m.breakRequested = true;
        assertEquals(new StopReason.UserBreak(), m.run(Set.of(), 5));
        assertEquals(0x0300, m.cpu.pc);
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
            @Override
            public int read(int off) { return 0; }
            @Override
            public void write(int off, int v) {}
            @Override
            public int peek(int off) { return 0; }
            @Override
            public void tick(int cycles) { ticked += cycles; }
            @Override
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

    /** Writes a vector into RAM (the system ROM area is RAM with no image attached). */
    private void vector(int at, int target) {
        m.bus.write(at, target & 0xFF);
        m.bus.write(at + 1, target >> 8);
    }

    /** A Device whose IRQ and NMI outputs the test sets directly. */
    private static final class Pins implements Device {
        boolean irq, nmi;
        @Override
        public int read(int off) { return 0; }
        @Override
        public void write(int off, int v) {}
        @Override
        public int peek(int off) { return 0; }
        @Override
        public boolean irq() { return irq; }
        @Override
        public boolean nmi() { return nmi; }
    }

    @Test
    void irqHeldHighIsEnteredOnlyOnceIIsClearAsItsOwnStep() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        m.loadProgram(bytes(0xEA, 0x58, 0xEA), 0x0200); // NOP; CLI; NOP
        vector(0xFFFE, 0x0300);
        pins.irq = true;

        assertEquals(2, m.step()); // NOP: the pin is now high, but I is set
        assertEquals(2, m.step()); // CLI
        assertEquals(0x0202, m.cpu.pc);

        assertEquals(7, m.step()); // interrupt entry, no handler instruction run
        assertEquals(0x0300, m.cpu.pc);
        assertEquals(Cpu.U | Cpu.I, m.cpu.p);
        assertEquals(0xFA, m.cpu.s);
        assertEquals(0x02, m.bus.peek(0x01FD)); // return address $0202, the NOP not yet run
        assertEquals(0x02, m.bus.peek(0x01FC));
        assertEquals(Cpu.U, m.bus.peek(0x01FB)); // pushed P: B clear, I was clear
        assertEquals(11, m.cycles);
    }

    @Test
    void nmiHeldHighIsEnteredOnceAndAgainAfterReleaseAndReassert() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        m.loadProgram(bytes(0x40), 0x0300);                // handler: RTI
        m.loadProgram(bytes(0xEA, 0xEA, 0xEA, 0xEA), 0x0200); // NOPs, I set: NMI ignores it
        vector(0xFFFA, 0x0300);
        pins.nmi = true;

        assertEquals(2, m.step()); // NOP at $0200; the Machine drives the pin high after it
        assertEquals(7, m.step()); // NMI entry
        assertEquals(0x0300, m.cpu.pc);
        assertEquals(Cpu.U | Cpu.I, m.bus.peek(0x01FB)); // pushed P: B clear
        assertEquals(6, m.step()); // RTI
        assertEquals(2, m.step()); // NOP at $0201: still held high, no second NMI
        assertEquals(0x0202, m.cpu.pc);

        pins.nmi = false;
        assertEquals(2, m.step()); // NOP at $0202, pin released
        pins.nmi = true;
        assertEquals(2, m.step()); // NOP at $0203, pin rises again
        assertEquals(7, m.step()); // second NMI entry
        assertEquals(0x0300, m.cpu.pc);
    }

    @Test
    void brkWithAZeroVectorStopsAsAStrayInterruptWithNoStateChanged() throws LoadException {
        m.loadProgram(bytes(0xEA, 0x00, 0xEA), 0x0200); // NOP; BRK (vector $FFFE still $0000)

        assertEquals(new StopReason.StrayInterrupt(0xFFFE), m.run(Set.of()));
        assertEquals(0x0201, m.cpu.pc);
        assertEquals(0xFD, m.cpu.s);
        assertEquals(Cpu.U | Cpu.I, m.cpu.p);
        assertEquals(2, m.cycles);
    }

    @Test
    void irqWithAZeroVectorStopsAsAStrayInterrupt() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        m.loadProgram(bytes(0x58, 0xEA), 0x0200); // CLI; NOP
        pins.irq = true;

        assertEquals(new StopReason.StrayInterrupt(0xFFFE), m.run(Set.of()));
        assertEquals(0x0201, m.cpu.pc);
        assertEquals(0xFD, m.cpu.s);
    }

    @Test
    void nmiWithAZeroVectorStopsAsAStrayInterrupt() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        m.loadProgram(bytes(0xEA, 0xEA), 0x0200);
        pins.nmi = true;

        assertEquals(new StopReason.StrayInterrupt(0xFFFA), m.run(Set.of()));
        assertEquals("stray interrupt: vector $FFFA = $0000", new StopReason.StrayInterrupt(0xFFFA).toString());
        assertEquals(0x0201, m.cpu.pc);
    }

    @Test
    void strayInterruptAlsoStopsAfterRomBoot() throws LoadException {
        var img = new byte[0x100]; // $FF00: BRK; vectors: reset $FF00, IRQ/BRK $0000
        img[0xFC] = 0x00; img[0xFD] = (byte) 0xFF;
        m.attachRom(m.systemRom, img);
        m.bootRom();

        assertEquals(new StopReason.StrayInterrupt(0xFFFE), m.run(Set.of()));
        assertEquals(0xFF00, m.cpu.pc);
    }

    @Test
    void attachedSystemRomIsRightAlignedAndReadOnly() throws LoadException {
        m.attachRom(m.systemRom, bytes(0x11, 0x22, 0x33));

        assertEquals(0x11, m.bus.read(0xFFFD));
        assertEquals(0x33, m.bus.peek(0xFFFF));
        m.bus.write(0xFFFF, 0x00);
        assertEquals(0x33, m.bus.read(0xFFFF));
        // the area below a short image is ROM too, reading $FF
        m.bus.write(0xE000, 0x00);
        assertEquals(0xFF, m.bus.read(0xE000));
        assertEquals(0xFF, m.bus.peek(0xFFFC));
    }

    @Test
    void romImageLargerThanItsAreaIsALoadError() throws LoadException {
        assertThrows(LoadException.class, () -> m.attachRom(m.systemRom, new byte[9 * 1024]));
        m.attachRom(m.systemRom, new byte[8 * 1024]);
        var e = assertThrows(LoadException.class, () -> m.attachRom(m.charRom, new byte[2049]));
        assertEquals("ROM image of 2049 bytes is larger than its area $C800-$CFFF", e.getMessage());
    }

    @Test
    void attachedCharacterRomIsLeftAlignedAndReadOnlyAndRefusesLoads() throws LoadException {
        m.attachRom(m.charRom, bytes(0x11, 0x22));

        assertEquals(0x11, m.bus.read(0xC800));
        assertEquals(0x22, m.bus.peek(0xC801));
        assertEquals(0xFF, m.bus.read(0xC802));
        assertEquals(0xFF, m.bus.read(0xCFFF));
        m.bus.write(0xC800, 0x00);
        assertEquals(0x11, m.bus.read(0xC800));
        var e = assertThrows(LoadException.class, () -> m.load(new byte[2], 0xC7FF));
        assertEquals("load $C7FF-$C800 overlaps $C800-$CFFF, which is not RAM", e.getMessage());
        m.load(new byte[1], 0xC7FF);
    }

    @Test
    void loadOverlappingAnAttachedRomIsALoadError() throws LoadException {
        m.attachRom(m.systemRom, new byte[0x100]);
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[1], 0xFFFF));
        assertThrows(LoadException.class, () -> m.loadProgram(new byte[1], 0xE000)); // the ROM area below the image
        m.loadProgram(new byte[0x100], 0x9F00);
    }

    @Test
    void resetResetsEveryDeviceRestartsTheCpuThroughFFFCAndKeepsMemory() throws LoadException {
        var dev = new Device() {
            int resets;
            @Override
            public int read(int off) { return 0; }
            @Override
            public void write(int off, int v) {}
            @Override
            public int peek(int off) { return 0; }
            @Override
            public void reset() { resets++; }
        };
        m.map(0xD600, 0x100, dev);
        m.loadProgram(bytes(0xA9, 0x01, 0x8D, 0x00, 0xD3), 0x0300); // LDA #1; STA $D300
        m.loadProgram(bytes(0x00, 0x03), 0xFFFC);                    // reset vector -> $0300
        m.cpu.s = 0x10;
        m.cpu.p = Cpu.U;
        m.bus.write(0xD300, 9); // pending exit, cleared by the Reset

        m.reset();

        assertEquals(1, dev.resets);
        assertEquals(0x0300, m.cpu.pc);
        assertEquals(0xFD, m.cpu.s);
        assertEquals(Cpu.U | Cpu.I, m.cpu.p);
        assertEquals(0xA9, m.bus.peek(0x0300));
        assertEquals(new StopReason.Exited(1), m.run(Set.of()));
    }

    @Test
    void loadCopiesBytesWithTheSameOverlapErrorsAndLeavesTheRegisters() throws LoadException {
        m.cpu.pc = 0x1234;
        m.cpu.a = 7;

        m.load(bytes(0xEA, 0x60), 0x0300);

        assertEquals(0xEA, m.bus.peek(0x0300));
        assertEquals(0x60, m.bus.peek(0x0301));
        assertEquals(0x1234, m.cpu.pc);
        assertEquals(7, m.cpu.a);
        var e = assertThrows(LoadException.class, () -> m.load(new byte[2], 0xCFFF));
        assertEquals("load $CFFF-$D000 overlaps $D000-$D0FF, which is not RAM", e.getMessage());
    }

    @Test
    void clearForRestartZeroesRamResetsDevicesAndCpuClearsCyclesAndAPendingExitKeepingTheRom() throws LoadException {
        var dev = new Device() {
            int resets;
            @Override
            public int read(int off) { return 0; }
            @Override
            public void write(int off, int v) {}
            @Override
            public int peek(int off) { return 0; }
            @Override
            public void reset() { resets++; }
        };
        m.map(0xD600, 0x100, dev);
        m.attachRom(m.systemRom, bytes(0x42));
        m.loadProgram(bytes(0xA2, 0x05, 0xDB), 0x0300); // LDX #5; undocumented
        m.run(Set.of());
        m.bus.write(0xD300, 9); // pending exit

        m.clearForRestart();

        assertEquals(1, dev.resets);
        assertEquals(0, m.bus.peek(0x0300));
        assertEquals(0x42, m.bus.peek(0xFFFF));
        assertEquals(0, m.cycles);
        assertEquals(0, m.cpu.pc); assertEquals(0, m.cpu.x);
        assertEquals(0xFD, m.cpu.s); assertEquals(Cpu.U | Cpu.I, m.cpu.p);
        m.loadProgram(bytes(0xEA, 0xDB), 0x0300); // NOP; undocumented
        assertEquals(new StopReason.Illegal(0x0301, 0xDB), m.run(Set.of())); // no pending exit stopped it after the NOP
    }

    @Test
    void romBootStartsFromTheResetVector() throws LoadException {
        var img = new byte[0x100]; // $FF00-$FFFF
        img[0xFC] = 0x00; img[0xFD] = (byte) 0xFF;
        m.attachRom(m.systemRom, img);

        m.bootRom();

        assertEquals(0xFF00, m.cpu.pc);
    }

    @Test
    void romBootRefusesAResetVectorOutsideTheImage() throws LoadException {
        var img = new byte[0x100];
        img[0xFC] = (byte) 0xFF; img[0xFD] = (byte) 0xFE; // $FEFF, just below the image
        m.attachRom(m.systemRom, img);

        assertThrows(LoadException.class, m::bootRom);
    }

    @Test
    void theConsoleTimerAndDiskHaveStateInMappingOrderAndTheExitDeviceHasNone() {
        assertEquals(List.of(DeviceState.Console.class, DeviceState.Timer.class, DeviceState.Disk.class),
                m.states().stream().map(Object::getClass).toList());
    }

    @Test
    void pinsNameEverySourceAssertingEachLevelTheNmiButtonIncluded() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        assertEquals(new machine.Pins(List.of(), List.of()), m.pins());

        m.write(0xD104, 0x01);
        m.write(0xD102, 0x81); // Timer: IRQ on, one-shot, reload 1
        m.loadProgram(bytes(0xEA), 0x0200);
        m.step(); // underflow: the Timer asserts IRQ
        pins.irq = pins.nmi = true;
        m.pressNmi();

        assertEquals(new machine.Pins(List.of("Timer", "Pins"), List.of("Pins", "NMI button")), m.pins());
    }

    @Test
    void theNmiButtonPressedWhileStoppedRaisesThePinAtOnceAndTheNextStepIsTheNmiEntry() throws LoadException {
        m.loadProgram(bytes(0x40), 0x0300);                // handler: RTI
        m.loadProgram(bytes(0xEA, 0xEA), 0x0200);
        vector(0xFFFA, 0x0300);

        m.pressNmi();
        assertTrue(m.cpu.nmiLine);
        assertEquals(new StopReason.Stepped(Entry.NMI, 0x0300), m.run(Set.of(), 1));
        assertEquals(new machine.Pins(List.of(), List.of()), m.pins()); // one press, one pin recompute
        assertFalse(m.cpu.nmiLine);

        m.pressNmi(); // again before any other Step: a new edge
        assertEquals(new StopReason.Stepped(Entry.NMI, 0x0300), m.run(Set.of(), 1));
        assertEquals(0xF7, m.cpu.s);
    }

    @Test
    void theNmiButtonPressedWhileSomethingHoldsNmiMakesNoNewEdge() throws LoadException {
        var pins = new Pins();
        m.map(0xD600, 0x100, pins);
        m.loadProgram(bytes(0x40), 0x0300);                // handler: RTI
        m.loadProgram(bytes(0xEA, 0xEA, 0xEA), 0x0200);
        vector(0xFFFA, 0x0300);
        pins.nmi = true;
        assertEquals(2, m.step()); // NOP; the pin rises after it
        assertEquals(7, m.step()); // NMI entry
        assertEquals(6, m.step()); // RTI, the pin still held

        m.pressNmi();

        assertEquals(2, m.step()); // NOP at $0201, no second entry
        assertEquals(0x0202, m.cpu.pc);
    }

    @Test
    void restartReleasesAPressedNmiButton() {
        m.pressNmi();
        m.clearForRestart();

        assertEquals(new machine.Pins(List.of(), List.of()), m.pins());
    }
}
