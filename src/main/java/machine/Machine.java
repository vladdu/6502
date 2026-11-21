package machine;

import cpu.Bus;
import cpu.Cpu;
import cpu.Entry;
import cpu.IllegalOpcode;
import cpu.StrayInterrupt;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * The CPU plus the memory map and Devices. The map is a table of 256 pages and the only record of what each address
 * is; the constructor's mapping calls define it, and an address no mapping covers is unmapped (reads $FF, writes
 * ignored).
 */
public final class Machine {
    /** Whole pages {@code [base, base + size)}; {@code device} gets offsets from {@code base}. */
    private record Mapping(int base, int size, Device device) {}

    private final int[] ram = new int[0x10000];
    /** The memory map, one entry per page; null is unmapped. */
    private final Mapping[] pages = new Mapping[0x100];
    /** The Devices, in mapping order, that get ticks, Reset and the pins; RAM and ROM areas are mapped but not here. */
    private final List<Device> devices = new ArrayList<>();
    private Integer exitCode;
    /** The installed Watchpoints, and which pages any of them covers (the fast path: one load per access). */
    private List<Watchpoint> watchpoints = List.of();
    private final boolean[] watchedPages = new boolean[0x100];
    /** Who the current Step's accesses are by: "Disk" in the Device ticks, null in the CPU part (named once it is done). */
    private String accessor;
    /** The current Step's Watchpoint hits: how many, and the first (complete once the Step is done). */
    private int hits;
    private StopReason.Watchpoint firstHit;
    /** Pressed and not yet released: the NMI button is in the NMI wired-OR until the next Step's pin recompute. */
    private boolean nmiButton;

    public final Bus bus = new MemoryMap();
    public final Cpu cpu = new Cpu(bus);
    /** Total cycles of all Steps so far. */
    public long cycles;
    /** Total Steps so far, counted next to {@link #cycles}. */
    public long steps;
    /**
     * Set from another thread (Ctrl-], or the Front panel posting work) to get the Machine's attention; {@link #run} reads it once
     * per Step and asks {@link #between}. Setting it should also unpark the Machine's thread, so a throttled Machine answers at once.
     */
    public volatile boolean breakRequested;
    /** Asked on the Machine's thread when {@link #run} sees {@link #breakRequested}: true when it served the request and the run goes on, false for a user break. */
    public BooleanSupplier between = () -> false;
    /** Target speed in Hz for every Step ({@link #run} and {@link #step} alike); 0 = unthrottled. */
    public long hz;
    /** Pacing anchor: cycle total c is due at nanoTime t0 + (c - c0) / hz; cycles since the last pace check. */
    private long t0 = System.nanoTime(), c0, sinceCheck;
    /** The Disk, on the bus with or without media; the only Device with a memory handle (DMA). */
    public final Disk disk = new Disk(bus, this::isRamPage);
    /** The system ROM area, holding the ROM boot image; a short image ends at the area's top, where the vectors are. */
    public final Rom systemRom;
    /** The Character ROM area, the glyphs of the future video Device; a short image starts at the area's base. */
    public final Rom charRom;

    /** A Machine whose Console never receives input and discards output. */
    public Machine() {
        this(new LinkedBlockingQueue<>(), OutputStream.nullOutputStream());
    }

    /** {@code consoleIn} is the Console's host-side receive queue (see {@link Console}); {@code consoleOut} gets transmitted bytes. */
    public Machine(BlockingQueue<Integer> consoleIn, OutputStream consoleOut) {
        // The memory map. $C000-$C7FF (text screen, colour RAM) and $A000-$BFFF (bitmap) are reserved for video, RAM until then.
        mapRam(0x0000, 0xC800);
        charRom = mapRom(0xC800, 0x0800, false);
        map(0xD000, 0x100, new Console(consoleIn, consoleOut));
        map(0xD100, 0x100, new Timer());
        map(0xD200, 0x100, disk);
        map(0xD300, 0x100, new ExitDevice(code -> exitCode = code));
        systemRom = mapRom(0xE000, 0x2000, true);
    }

    /** The {@link DeviceState#describe} lines of every Device that has state, in mapping order. */
    public List<String> describe() {
        return states().stream().map(DeviceState::describe).toList();
    }

    /** The state of every Device that has one, in mapping order; the Exit device has none. */
    public List<DeviceState> states() {
        return devices.stream().map(Device::state).filter(Objects::nonNull).toList();
    }

    /** The sources asserting IRQ and NMI now: Devices in mapping order, then the NMI button. */
    public Pins pins() {
        var irq = new ArrayList<String>();
        var nmi = new ArrayList<String>();
        for (Device d : devices) {
            // shortcut: a Device is named by its class, fine while those are the glossary names; give Device a name() when one isn't
            if (d.irq()) irq.add(d.getClass().getSimpleName());
            if (d.nmi()) nmi.add(d.getClass().getSimpleName());
        }
        if (nmiButton) nmi.add("NMI button");
        return new Pins(List.copyOf(irq), List.copyOf(nmi));
    }

    /** The NMI button: the pin rises at once, so the next Step is the NMI entry, unless something already holds it high. */
    public void pressNmi() {
        nmiButton = true;
        cpu.raiseNmi();
    }

    /** Maps a Device over whole unmapped pages {@code [base, base + size)}. */
    public void map(int base, int size, Device device) {
        place(base, size, device);
        devices.add(device);
    }

    private void mapRam(int base, int size) {
        place(base, size, new Ram(base));
    }

    /** A ROM area over {@code [base, base + size)}: RAM until an image is attached. */
    private Rom mapRom(int base, int size, boolean endAligned) {
        var rom = new Rom(base, size, endAligned, new Ram(base));
        place(base, size, rom);
        return rom;
    }

    /** Puts a mapping on whole pages that nothing maps yet. */
    private void place(int base, int size, Device device) {
        if (((base | size) & 0xFF) != 0 || size <= 0 || base < 0 || base + size > 0x10000)
            throw new IllegalArgumentException(String.format("$%X bytes at $%X are not whole pages of memory", size, base));
        int from = base >> 8, to = (base + size) >> 8;
        for (int p = from; p < to; p++)
            if (pages[p] != null) throw new IllegalArgumentException(String.format("page $%02X is already mapped", p));
        Arrays.fill(pages, from, to, new Mapping(base, size, device));
    }

    /** Whether page {@code p} is RAM: the pages {@link #load} and Disk DMA may use. */
    boolean isRamPage(int p) {
        Device d = pages[p] == null ? null : pages[p].device;
        return d instanceof Ram || (d instanceof Rom rom && !rom.attached());
    }

    /** Attaches an image to a ROM area, which becomes read-only over its whole size; the RAM under it is hidden. */
    public void attachRom(Rom area, byte[] image) throws LoadException {
        if (image.length > area.size)
            throw new LoadException(String.format("ROM image of %d bytes is larger than its area $%04X-$%04X",
                    image.length, area.base, area.base + area.size - 1));
        area.attach(image);
    }

    /** ROM boot: a hardware Reset, refused when the reset vector at $FFFC points outside the system ROM's image. */
    public void bootRom() throws LoadException {
        int vector = bus.peek(0xFFFC) | bus.peek(0xFFFD) << 8;
        if (!systemRom.holds(vector))
            throw new LoadException(String.format("reset vector $%04X points outside the ROM image", vector));
        reset();
    }

    /** Program mode: copy the image to {@code org}, PC there, S=$FD, I set, A/X/Y=0. */
    public void loadProgram(byte[] image, int org) throws LoadException {
        load(image, org);
        cpu.pc = org;
        cpu.s = 0xFD;
        cpu.p = Cpu.U | Cpu.I;
        cpu.a = cpu.x = cpu.y = 0;
    }

    /** Copies the image to {@code org}, registers untouched; refused unless every byte lands on a RAM page. */
    public void load(byte[] image, int org) throws LoadException {
        int end = org + image.length;
        if (org < 0 || org > 0xFFFF || end > 0x10000)
            throw new LoadException(String.format("%d bytes at $%X do not fit below $10000", image.length, org));
        for (int a = org; a < end; a = (a | 0xFF) + 1) {
            if (isRamPage(a >> 8)) continue;
            Mapping m = pages[a >> 8];
            int from = m == null ? a & 0xFF00 : m.base, to = m == null ? from + 0xFF : m.base + m.size - 1;
            throw new LoadException(String.format("load $%04X-$%04X overlaps $%04X-$%04X, which is not RAM", org, end - 1, from, to));
        }
        for (int i = 0; i < image.length; i++) ram[org + i] = image[i] & 0xFF;
    }

    /** Watches exactly {@code list} from the next Step on, replacing what was installed; Reset and Restart keep it. */
    public void watch(Collection<Watchpoint> list) {
        watchpoints = List.copyOf(list);
        Arrays.fill(watchedPages, false);
        for (Watchpoint w : watchpoints) Arrays.fill(watchedPages, w.start() >> 8, ((w.start() + w.length() - 1) >> 8) + 1, true);
    }

    /** Records a Bus access on a watched page as a hit if a Watchpoint covers it; only a Step's own hits survive to its end. */
    private void access(int a, boolean write, int old, int value) {
        for (Watchpoint w : watchpoints)
            if (a >= w.start() && a < w.start() + w.length() && (write ? w.writes() : w.reads())) {
                if (hits++ == 0) firstHit = new StopReason.Watchpoint(a, write, old, value, accessor, 0);
                return;
            }
    }

    /** Hardware Reset: every Device reset, pending exit cleared, CPU restarted through $FFFC; memory kept. */
    public void reset() {
        for (Device d : devices) d.reset();
        exitCode = null;
        cpu.reset();
    }

    /**
     * The Machine part of a Restart, back to the state as constructed: RAM zeroed, every Device reset, pending exit cleared, cycles and Steps 0,
     * CPU registers as constructed. ROM images and the Disk's media stay attached.
     */
    public void clearForRestart() {
        Arrays.fill(ram, 0);
        for (Device d : devices) d.reset();
        exitCode = null;
        nmiButton = false;
        cycles = 0;
        steps = 0;
        cpu.clear();
    }

    /**
     * A write through the Bus from outside a Step (the Monitor's), then a zero-cycle tick: it is its own issuing Step.
     * Returns the exit code if an Exit device write is pending, else null.
     */
    public Integer write(int addr, int v) {
        bus.write(addr, v);
        for (Device d : devices) d.tick(0);
        return exitCode;
    }

    /**
     * One CPU Step, then tick every Device with its cycles, then wired-OR their IRQ/NMI onto the CPU pins; Watchpoint hits
     * are recorded throughout. Outside callers use {@link #run}.
     */
    int step() {
        int at = cpu.pc;
        hits = 0; // drops hits from outside a Step: Monitor writes, Reset's vector read
        accessor = null;
        int c = cpu.step();
        cycles += c;
        steps++;
        accessor = "Disk"; // only Disk DMA reaches the Bus from a tick
        boolean irq = false, nmi = false;
        for (Device d : devices) {
            d.tick(c);
            irq |= d.irq();
            nmi |= d.nmi();
        }
        if (hits > 0) {
            var f = firstHit;
            String by = f.by() != null ? f.by()
                    : cpu.entry == Entry.NMI || cpu.entry == Entry.IRQ ? cpu.entry + " entry" : String.format("$%04X", at);
            firstHit = new StopReason.Watchpoint(f.address(), f.write(), f.old(), f.value(), by, hits - 1);
        }
        cpu.irqLine = irq;
        cpu.nmiLine = nmi;
        nmiButton = false;
        sinceCheck += c;
        if (hz > 0 && sinceCheck >= Math.max(1, Math.round(hz / 1000.0))) pace();
        return c;
    }

    /** After each 1 ms slice of cycles: park until they are due; more than 20 ms behind, rebase instead of catching up. */
    private void pace() {
        sinceCheck = 0;
        if (System.nanoTime() - due() > 20_000_000) anchor();
        else waitDue();
    }

    /** The nanoTime the cycles so far are due at. */
    private long due() {
        return t0 + (long) ((cycles - c0) * 1e9 / hz);
    }

    /** Parks until the cycles so far are due; a {@link #breakRequested} cuts it short, and {@link #run} waits out the rest once served. */
    private void waitDue() {
        for (long d; (d = due() - System.nanoTime()) > 0 && !breakRequested; ) LockSupport.parkNanos(d);
    }

    private void anchor() {
        t0 = System.nanoTime();
        c0 = cycles;
    }

    /** Runs Steps until a Stop. A breakpoint at the starting PC does not stop the first Step. */
    public StopReason run(Set<Integer> breakpoints) {
        return run(breakpoints, Long.MAX_VALUE);
    }

    /** {@link #run(Set)}, but also stopping as {@link StopReason.Stepped} after {@code maxSteps} Steps. */
    public StopReason run(Set<Integer> breakpoints, long maxSteps) {
        anchor(); // no catch-up for the time spent stopped
        for (long n = 0; ; n++) {
            if (n > 0 && breakpoints.contains(cpu.pc)) return new StopReason.Breakpoint(cpu.pc);
            if (n >= maxSteps) return new StopReason.Stepped(n > 0 ? cpu.entry : null, cpu.pc);
            if (breakRequested) {
                breakRequested = false;
                long paced = hz;
                if (!between.getAsBoolean()) return new StopReason.UserBreak();
                if (exitCode != null) return new StopReason.Exited(exitCode); // a write to the Exit device
                if (hz != paced) anchor(); // a new speed counts from now
                else if (hz > 0) waitDue();
            }
            try {
                step();
            } catch (IllegalOpcode e) {
                return new StopReason.Illegal(e.pc, e.opcode);
            } catch (StrayInterrupt e) {
                return new StopReason.StrayInterrupt(e.vector);
            }
            if (exitCode != null) return new StopReason.Exited(exitCode);
            if (hits > 0) return firstHit;
        }
    }

    /** Every access goes through the page table. */
    private final class MemoryMap implements Bus {
        @Override
        public int read(int a) {
            a &= 0xFFFF;
            Mapping m = pages[a >> 8];
            int v = m == null ? 0xFF : m.device.read(a - m.base) & 0xFF;
            if (watchedPages[a >> 8]) access(a, false, v, v);
            return v;
        }

        @Override
        public void write(int a, int v) {
            a &= 0xFFFF;
            Mapping m = pages[a >> 8];
            if (watchedPages[a >> 8]) access(a, true, peek(a), v & 0xFF);
            if (m != null) m.device.write(a - m.base, v & 0xFF);
        }

        @Override
        public int peek(int a) {
            a &= 0xFFFF;
            Mapping m = pages[a >> 8];
            return m == null ? 0xFF : m.device.peek(a - m.base) & 0xFF;
        }
    }

    /** The RAM under a range of pages; it implements {@link Device} only so the bus reaches it like any mapping. */
    private final class Ram implements Device {
        private final int base;

        Ram(int base) {
            this.base = base;
        }

        @Override
        public int read(int off) { return ram[base + off]; }
        @Override
        public void write(int off, int v) { ram[base + off] = v; }
        @Override
        public int peek(int off) { return ram[base + off]; }
    }
}
