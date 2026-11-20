package machine;

import cpu.Bus;
import cpu.Cpu;
import cpu.IllegalOpcode;
import cpu.StrayInterrupt;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** The CPU plus the memory map and Devices. */
public final class Machine {
    public static final int CHAR_ROM = 0xC800, IO_START = 0xD000, IO_END = 0xE000, EXIT = 0xD300;
    public static final int CONSOLE = 0xD000;

    private record Mapping(int base, int size, Device device) {}

    private final int[] ram = new int[0x10000];
    private final List<Mapping> mappings = new ArrayList<>();
    private Integer exitCode;
    /** Attached ROM image, right-aligned to end at $FFFF; null when none (ROM space is then RAM). */
    private byte[] rom;

    public final Bus bus = new MemoryMap();
    public final Cpu cpu = new Cpu(bus);
    /** Total cycles of all Steps so far. */
    public long cycles;
    /** Set from the host thread (Ctrl-]); {@link #run} stops before the next Step. */
    public volatile boolean breakRequested;
    /** The Disk at $D200, on the bus with or without media; the only Device with a memory handle (DMA). */
    public final Disk disk = new Disk(bus, () -> rom != null);

    /** A Machine whose Console never receives input and discards output. */
    public Machine() {
        this(new LinkedBlockingQueue<>(), OutputStream.nullOutputStream());
    }

    /** {@code consoleIn} is the Console's host-side receive queue (see {@link Console}); {@code consoleOut} gets transmitted bytes. */
    public Machine(BlockingQueue<Integer> consoleIn, OutputStream consoleOut) {
        map(CONSOLE, 0x100, new Console(consoleIn, consoleOut));
        map(0xD100, 0x100, new Timer());
        map(Disk.BASE, 0x100, disk);
        map(EXIT, 1, new ExitDevice(code -> exitCode = code));
    }

    /** Maps a Device at {@code [base, base + size)} inside the I/O region. */
    public void map(int base, int size, Device device) {
        mappings.add(new Mapping(base, size, device));
    }

    /** Attaches a ROM image of up to 8K, right-aligned to end at $FFFF; ROM space becomes read-only. */
    public void attachRom(byte[] image) throws LoadException {
        if (image.length > 0x10000 - IO_END)
            throw new LoadException(String.format("ROM image of %d bytes is larger than 8K", image.length));
        rom = image.clone();
    }

    /** ROM boot: a hardware Reset, refused when the reset vector at $FFFC points outside the attached image. */
    public void bootRom() throws LoadException {
        int vector = bus.peek(0xFFFC) | bus.peek(0xFFFD) << 8;
        if (rom == null || vector < 0x10000 - rom.length)
            throw new LoadException(String.format("reset vector $%04X points outside the ROM image", vector));
        reset();
    }

    /** Program mode: copy the image to {@code org}, PC there, S=$FD, I set, A/X/Y=0. */
    public void loadProgram(byte[] image, int org) throws LoadException {
        int end = org + image.length;
        if (org < 0 || org > 0xFFFF || end > 0x10000)
            throw new LoadException(String.format("%d bytes at $%X do not fit below $10000", image.length, org));
        if (org < IO_END && end > IO_START)
            throw new LoadException(String.format("load $%04X-$%04X overlaps the I/O region $D000-$DFFF", org, end - 1));
        if (rom != null && end > IO_END)
            throw new LoadException(String.format("load $%04X-$%04X overlaps the attached ROM $E000-$FFFF", org, end - 1));
        for (int i = 0; i < image.length; i++) ram[org + i] = image[i] & 0xFF;
        cpu.pc = org;
        cpu.s = 0xFD;
        cpu.p = Cpu.U | Cpu.I;
        cpu.a = cpu.x = cpu.y = 0;
    }

    /** Hardware Reset: every Device reset, pending exit cleared, CPU restarted through $FFFC; memory kept. */
    public void reset() {
        for (Mapping m : mappings) m.device.reset();
        exitCode = null;
        cpu.reset();
    }

    /** One CPU Step, then tick every Device with its cycles, then wired-OR their IRQ/NMI onto the CPU pins. */
    public int step() {
        int c = cpu.step();
        cycles += c;
        boolean irq = false, nmi = false;
        for (Mapping m : mappings) {
            m.device.tick(c);
            irq |= m.device.irq();
            nmi |= m.device.nmi();
        }
        cpu.irqLine = irq;
        cpu.nmiLine = nmi;
        return c;
    }

    /** Runs Steps until a stop. A breakpoint at the starting PC does not stop the first Step. */
    public StopReason run(Set<Integer> breakpoints) {
        for (boolean first = true; ; first = false) {
            if (!first && breakpoints.contains(cpu.pc)) return new StopReason.Breakpoint(cpu.pc);
            if (breakRequested) {
                breakRequested = false;
                return new StopReason.UserBreak();
            }
            try {
                step();
            } catch (IllegalOpcode e) {
                return new StopReason.Illegal(e.pc, e.opcode);
            } catch (StrayInterrupt e) {
                return new StopReason.StrayInterrupt(e.vector);
            }
            if (exitCode != null) return new StopReason.Exited(exitCode);
        }
    }

    /** RAM below the Character ROM window and from $E000 up (ROM instead when attached); I/O by Device range; the rest unmapped ($FF). */
    private final class MemoryMap implements Bus {
        public int read(int a) {
            a &= 0xFFFF;
            if (isRam(a)) return ram[a];
            if (a >= IO_END) return romRead(a);
            Mapping m = find(a);
            return m == null ? 0xFF : m.device.read(a - m.base) & 0xFF;
        }

        public void write(int a, int v) {
            a &= 0xFFFF;
            if (isRam(a)) {
                ram[a] = v & 0xFF;
                return;
            }
            Mapping m = find(a);
            if (m != null) m.device.write(a - m.base, v & 0xFF);
        }

        public int peek(int a) {
            a &= 0xFFFF;
            if (isRam(a)) return ram[a];
            if (a >= IO_END) return romRead(a);
            Mapping m = find(a);
            return m == null ? 0xFF : m.device.peek(a - m.base) & 0xFF;
        }

        private boolean isRam(int a) {
            return a < CHAR_ROM || a >= IO_END && rom == null;
        }

        /** ROM space with an image attached: the image, or $FF below a short one. */
        private int romRead(int a) {
            int off = a - (0x10000 - rom.length);
            return off < 0 ? 0xFF : rom[off] & 0xFF;
        }

        private Mapping find(int a) {
            if (a < IO_START) return null; // Character ROM window
            for (Mapping m : mappings) if (a >= m.base && a < m.base + m.size) return m;
            return null;
        }
    }
}
