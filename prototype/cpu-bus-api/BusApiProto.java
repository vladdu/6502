// PROTOTYPE, throwaway. Answers: "CPU, bus and Device API shape" (vladdu/6502#4).
// Run: java prototype/cpu-bus-api/BusApiProto.java
//
// Question: how does the CPU reach memory and Devices, how do Devices learn elapsed
// cycles, how are IRQ/NMI wired and sampled, and how do Program mode / ROM boot /
// Monitor drive the CPU? Each scenario below prints full state after every step.
//
// CPU here implements only a handful of opcodes, just enough to push the API around.

import java.util.*;
import java.util.function.IntConsumer;

public class BusApiProto {

    // ---------------------------------------------------------------- Bus

    /** What the CPU sees. Addresses and values are ints, masked to 16/8 bits. */
    interface Bus {
        int read(int addr);
        void write(int addr, int value);
        /** Side-effect-free read for the Monitor/Disassembler. */
        int peek(int addr);
    }

    /** Plain 64 KiB RAM: the whole Bus for SingleStepTests. */
    static final class Ram implements Bus {
        final int[] mem = new int[0x10000];
        public int read(int a) { return mem[a]; }
        public void write(int a, int v) { mem[a] = v & 0xFF; }
        public int peek(int a) { return mem[a]; }
    }

    // ---------------------------------------------------------------- Device

    /** A Device sees offsets relative to its base, never absolute addresses. */
    interface Device {
        int read(int offset);
        void write(int offset, int value);
        /** Default: reads have no side effects. Devices whose reads do (Console data) override. */
        default int peek(int offset) { return read(offset); }
        /** Called after every CPU step with the cycles that step took. */
        default void tick(int cycles) {}
        /** Level of this Device's IRQ output. Machine ORs all of them (wired-OR). */
        default boolean irq() { return false; }
        /** Level of this Device's NMI output. CPU detects the rising edge. */
        default boolean nmi() { return false; }
    }

    record Mapping(int base, int size, Device device) {
        boolean contains(int a) { return a >= base && a < base + size; }
    }

    /** RAM + ROM + mapped Devices. Ranges, not pages: granularity is the memory map's call. */
    static final class MachineBus implements Bus {
        final int[] mem = new int[0x10000];
        final List<Mapping> io = new ArrayList<>();
        int romStart = 0x10000; // writes at or above are ignored

        Mapping find(int a) {
            for (Mapping m : io) if (m.contains(a)) return m; // shortcut: linear scan, fine for <10 Devices
            return null;
        }
        public int read(int a) { var m = find(a); return m != null ? m.device.read(a - m.base) : mem[a]; }
        public void write(int a, int v) {
            var m = find(a);
            if (m != null) m.device.write(a - m.base, v & 0xFF);
            else if (a < romStart) mem[a] = v & 0xFF;
        }
        public int peek(int a) { var m = find(a); return m != null ? m.device.peek(a - m.base) : mem[a]; }
        /** Loader path: writes RAM/ROM bytes directly, ignoring ROM protection and Devices. */
        void load(int addr, int... bytes) { for (int b : bytes) mem[addr++ & 0xFFFF] = b & 0xFF; }
    }

    // ---------------------------------------------------------------- CPU

    static final class IllegalOpcode extends RuntimeException {
        final int pc, opcode;
        IllegalOpcode(int pc, int opcode) { super(String.format("illegal opcode $%02X at $%04X", opcode, pc)); this.pc = pc; this.opcode = opcode; }
    }

    static final class Cpu {
        static final int C = 0x01, Z = 0x02, I = 0x04, D = 0x08, B = 0x10, U = 0x20, V = 0x40, N = 0x80;

        // Registers are plain fields: tests and the Monitor set/read them directly.
        int a, x, y, s = 0xFD, pc, p = U | I;
        // Input pins, set by the Machine before each step.
        boolean irqLine, nmiLine;
        private boolean nmiPrev, nmiPending;
        /** What the last step was: an instruction or an interrupt entry. For Monitor display. */
        String lastEvent = "";

        final Bus bus;
        Cpu(Bus bus) { this.bus = bus; }

        void reset() {
            pc = bus.read(0xFFFC) | bus.read(0xFFFD) << 8;
            s = 0xFD; p = U | I; lastEvent = "RESET";
        }

        /**
         * One step = one instruction OR one interrupt entry (7 cycles, PC lands on the vector,
         * no handler instruction executed yet). Returns cycles. Interrupts are sampled here, at
         * the instruction boundary, against pin levels set before the call.
         * Throws IllegalOpcode with PC left on the opcode.
         */
        int step() {
            if (nmiLine && !nmiPrev) nmiPending = true;
            nmiPrev = nmiLine;
            if (nmiPending) { nmiPending = false; interrupt(0xFFFA); lastEvent = "NMI taken"; return 7; }
            if (irqLine && (p & I) == 0) { interrupt(0xFFFE); lastEvent = "IRQ taken"; return 7; }

            int at = pc, op = bus.read(pc);
            pc = (pc + 1) & 0xFFFF;
            int cycles = switch (op) {
                case 0xA9 -> { a = nz(fetch()); lastEvent = "LDA #"; yield 2; }
                case 0xAD -> { a = nz(bus.read(fetchWord())); lastEvent = "LDA abs"; yield 4; }
                case 0x8D -> { bus.write(fetchWord(), a); lastEvent = "STA abs"; yield 4; }
                case 0x2C -> { int m = bus.read(fetchWord());
                               p = (p & ~(N | V | Z)) | (m & (N | V)) | ((a & m) == 0 ? Z : 0); lastEvent = "BIT abs"; yield 4; }
                case 0x10 -> { int off = (byte) fetch(); lastEvent = "BPL"; yield branch((p & N) == 0, off); }
                case 0xE8 -> { x = nz(x + 1); lastEvent = "INX"; yield 2; }
                case 0x4C -> { pc = fetchWord(); lastEvent = "JMP abs"; yield 3; }
                case 0x58 -> { p &= ~I; lastEvent = "CLI"; yield 2; }
                case 0x78 -> { p |= I; lastEvent = "SEI"; yield 2; }
                case 0x40 -> { p = (pull() & ~B) | U; pc = pull() | pull() << 8; lastEvent = "RTI"; yield 6; }
                case 0xEA -> { lastEvent = "NOP"; yield 2; }
                default -> { pc = at; throw new IllegalOpcode(at, op); }
            };
            return cycles;
        }

        private void interrupt(int vector) {
            push(pc >> 8); push(pc & 0xFF); push((p & ~B) | U); // B clear for IRQ/NMI; BRK would push B set
            p |= I;
            pc = bus.read(vector) | bus.read(vector + 1) << 8;
        }
        private int branch(boolean taken, int off) {
            if (!taken) return 2;
            int old = pc; pc = (pc + off) & 0xFFFF;
            return (old & 0xFF00) == (pc & 0xFF00) ? 3 : 4;
        }
        private int fetch() { int v = bus.read(pc); pc = (pc + 1) & 0xFFFF; return v; }
        private int fetchWord() { return fetch() | fetch() << 8; }
        private int nz(int v) { v &= 0xFF; p = (p & ~(N | Z)) | (v & N) | (v == 0 ? Z : 0); return v; }
        private void push(int v) { bus.write(0x100 | s, v & 0xFF); s = (s - 1) & 0xFF; }
        private int pull() { s = (s + 1) & 0xFF; return bus.read(0x100 | s); }

        String regs() {
            return String.format("PC=%04X A=%02X X=%02X Y=%02X S=%02X P=%s", pc, a, x, y, s, flags());
        }
        private String flags() {
            String n = "NV-BDIZC"; var sb = new StringBuilder();
            for (int i = 7; i >= 0; i--) sb.append((p >> i & 1) != 0 ? n.charAt(7 - i) : '.');
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- Machine

    sealed interface StopReason {
        record Breakpoint(int pc) implements StopReason { public String toString() { return String.format("Breakpoint[$%04X]", pc); } }
        record Exited(int code) implements StopReason {}
        record Illegal(int pc, int opcode) implements StopReason { public String toString() { return String.format("Illegal[$%02X at $%04X]", opcode, pc); } }
        record UserBreak() implements StopReason {}      // Ctrl-] from the Console host thread
        record StepLimit() implements StopReason {}      // prototype safety net only
    }

    static final class Machine {
        final MachineBus bus = new MachineBus();
        final Cpu cpu = new Cpu(bus);
        final List<Device> devices = new ArrayList<>();
        long cycles;
        Integer exitCode;                 // set by the Exit device
        volatile boolean breakRequested;  // set from the Console host thread

        void map(int base, int size, Device d) { bus.io.add(new Mapping(base, size, d)); devices.add(d); }

        /** One CPU step, then tick every Device with its cycles, then refresh interrupt pins. */
        int step() {
            int c = cpu.step();
            cycles += c;
            boolean irq = false, nmi = false;
            for (Device d : devices) { d.tick(c); irq |= d.irq(); nmi |= d.nmi(); }
            cpu.irqLine = irq; cpu.nmiLine = nmi;
            return c;
        }

        /** Monitor "go": runs until a stop. A breakpoint at the starting PC does not stop the first step. */
        StopReason run(Set<Integer> breakpoints, int maxSteps, Runnable afterStep) {
            for (int n = 0; n < maxSteps; n++) {
                if (n > 0 && breakpoints.contains(cpu.pc)) return new StopReason.Breakpoint(cpu.pc);
                if (breakRequested) { breakRequested = false; return new StopReason.UserBreak(); }
                try { step(); } catch (IllegalOpcode e) { return new StopReason.Illegal(e.pc, e.opcode); }
                afterStep.run();
                if (exitCode != null) return new StopReason.Exited(exitCode);
            }
            return new StopReason.StepLimit();
        }

        /** Program mode: load bytes at addr, PC there, skip the reset vector. */
        void programMode(int addr, int... bytes) { bus.load(addr, bytes); cpu.pc = addr; }
        /** ROM boot: image at top of memory, ROM write-protected, PC from reset vector. */
        void romBoot(int romStart, int[] image) { bus.load(romStart, image); bus.romStart = romStart; cpu.reset(); }
    }

    // ---------------------------------------------------------------- Stub Devices

    /** Counts down cycles; raises IRQ at zero. Read offset 0 = status (bit7 = fired) and acknowledges. */
    static final class Timer implements Device {
        int reload, counter; boolean fired;
        Timer(int reload) { this.reload = this.counter = reload; }
        public int read(int o) { int v = fired ? 0x80 : 0; if (o == 0) fired = false; return v; }
        public int peek(int o) { return fired ? 0x80 : 0; }
        public void write(int o, int v) { if (o == 1) { reload = counter = v; } }
        public void tick(int c) { counter -= c; while (counter <= 0) { counter += reload; fired = true; } }
        public boolean irq() { return fired; }
        public String toString() { return "Timer[counter=" + counter + " fired=" + fired + "]"; }
    }

    /** Offset 0 = data (read pops RX byte, write transmits), offset 1 = status (bit7 = RX ready, bit6 = TX ready). */
    static final class Console implements Device {
        final Deque<Integer> rx = new ArrayDeque<>(); final StringBuilder tx = new StringBuilder();
        public int read(int o) { return o == 0 ? (rx.isEmpty() ? 0 : rx.poll()) : status(); }
        public int peek(int o) { return o == 0 ? (rx.isEmpty() ? 0 : rx.peek()) : status(); }
        public void write(int o, int v) { if (o == 0) tx.append((char) v); }
        int status() { return (rx.isEmpty() ? 0 : 0x80) | 0x40; }
        public String toString() { return "Console[rx=" + rx + " tx=\"" + tx + "\"]"; }
    }

    /** NMI source driven from outside (the Monitor, or Dormann's interrupt feedback register). */
    static final class NmiButton implements Device {
        boolean pressed;
        public int read(int o) { return pressed ? 1 : 0; }
        public void write(int o, int v) { pressed = (v & 1) != 0; }
        public boolean nmi() { return pressed; }
        public String toString() { return "NmiButton[pressed=" + pressed + "]"; }
    }

    static final class ExitDevice implements Device {
        final IntConsumer onExit;
        ExitDevice(IntConsumer onExit) { this.onExit = onExit; }
        public int read(int o) { return 0; }
        public void write(int o, int v) { onExit.accept(v); }
        public String toString() { return "Exit"; }
    }

    // ---------------------------------------------------------------- Scenarios

    static void show(Machine m) {
        System.out.printf("  %-10s %s  cyc=%-4d irq=%s nmi=%s  %s%n",
                m.cpu.lastEvent, m.cpu.regs(), m.cycles, m.cpu.irqLine ? 1 : 0, m.cpu.nmiLine ? 1 : 0, m.devices);
    }

    static Machine standardMachine() {
        var m = new Machine();
        // Addresses below are placeholders; the memory map ticket decides them.
        m.map(0xD000, 2, new Console());
        m.map(0xD010, 2, new Timer(30));
        m.map(0xD020, 1, new NmiButton());
        m.map(0xD0F0, 1, new ExitDevice(code -> m.exitCode = code));
        return m;
    }

    static void scenario(String title, String watch) {
        System.out.println("\n=== " + title + " ===\n  watch: " + watch);
    }

    public static void main(String[] args) {
        scenario("1. Plain RAM bus (SingleStepTests shape)",
                "CPU runs on a 64K Ram with no Machine at all; test sets registers, steps once, reads cycles.");
        {
            var ram = new Ram(); var cpu = new Cpu(ram);
            ram.mem[0x0200] = 0xAD; ram.mem[0x0201] = 0x34; ram.mem[0x0202] = 0x12; ram.mem[0x1234] = 0x80;
            cpu.pc = 0x0200; cpu.p = 0x20; // test data: bit5 set, B clear
            int c = cpu.step();
            System.out.printf("  %s  cycles=%d%n", cpu.regs(), c);
        }

        scenario("2. Program mode: Console out then Exit device",
                "Machine stops with Exited(code) right after the store to the Exit device.");
        {
            var m = standardMachine();
            m.programMode(0x0200, 0xA9, 'H', 0x8D, 0x00, 0xD0, 0xA9, 'i', 0x8D, 0x00, 0xD0,
                                  0xA9, 0x00, 0x8D, 0xF0, 0xD0);
            System.out.println("  -> " + m.run(Set.of(), 100, () -> show(m)));
        }

        scenario("3. Timer IRQ: entry is its own step, then handler acks by reading status",
                "Timer fires every 30 cycles; IRQ is taken only after CLI; 'IRQ taken' step costs 7 and executes nothing.");
        {
            var m = standardMachine();
            m.bus.load(0xFFFE, 0x00, 0x03);                      // IRQ vector -> $0300
            m.bus.load(0x0300, 0x2C, 0x10, 0xD0, 0x40);          // BIT $D010 (ack, A untouched), RTI
            m.programMode(0x0200, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8, 0xE8,
                                  0x58, 0xE8, 0xE8, 0xA9, 0x00, 0x8D, 0xF0, 0xD0); // INX x12, CLI, INX, INX, exit
            System.out.println("  -> " + m.run(Set.of(), 100, () -> show(m)));
        }

        scenario("4. NMI is edge-triggered",
                "Holding the NMI line high causes exactly one NMI; releasing and re-asserting causes another.");
        {
            var m = standardMachine();
            var btn = (NmiButton) m.devices.get(2);
            m.bus.load(0xFFFA, 0x00, 0x04);
            m.bus.load(0x0400, 0x40);                            // NMI handler: RTI
            m.programMode(0x0200, 0xEA, 0x4C, 0x00, 0x02);       // NOP; JMP $0200
            for (int i = 0; i < 9; i++) {
                if (i == 1) btn.pressed = true;                  // Monitor presses NMI
                if (i == 5) btn.pressed = false;
                if (i == 6) btn.pressed = true;
                // pins are refreshed from Devices after each step, so refresh before the first too
                m.cpu.nmiLine = btn.nmi();
                m.step(); show(m);
            }
        }

        scenario("5. Monitor peek vs CPU read on a side-effecting register",
                "Monitor memory dump of $D000 must not consume the RX byte; the CPU's LDA does.");
        {
            var m = standardMachine();
            var con = (Console) m.devices.get(0);
            con.rx.add((int) 'k');
            System.out.printf("  peek $D000=%02X  %s%n", m.bus.peek(0xD000), con);
            System.out.printf("  peek $D000=%02X  %s%n", m.bus.peek(0xD000), con);
            m.programMode(0x0200, 0xAD, 0x00, 0xD0);
            m.step(); show(m);
        }

        scenario("6. Breakpoint, user break, illegal opcode",
                "Run stops before executing the breakpoint PC; 'go' again steps off it; $DB stops with PC on the opcode.");
        {
            var m = standardMachine();
            m.programMode(0x0200, 0xEA, 0xEA, 0xEA, 0xDB);
            var bps = Set.of(0x0201);
            System.out.println("  -> " + m.run(bps, 100, () -> show(m)));
            System.out.println("  -> " + m.run(bps, 100, () -> show(m)));
            show(m);
            m.cpu.pc = 0x0200; m.breakRequested = true;          // as if Ctrl-] arrived from the host thread
            System.out.println("  -> " + m.run(bps, 100, () -> show(m)));
        }

        scenario("7. ROM boot",
                "PC comes from $FFFC; writes into ROM are ignored.");
        {
            var m = standardMachine();
            int[] rom = new int[0x1000];                         // $F000-$FFFF, placeholder size
            rom[0] = 0xA9; rom[1] = 0x55; rom[2] = 0x8D; rom[3] = 0x00; rom[4] = 0xF0; // LDA #$55; STA $F000 (into ROM)
            rom[5] = 0xA9; rom[6] = 0x00; rom[7] = 0x8D; rom[8] = 0xF0; rom[9] = 0xD0;
            rom[0xFFC] = 0x00; rom[0xFFD] = 0xF0;
            m.romBoot(0xF000, rom);
            show(m);
            System.out.println("  -> " + m.run(Set.of(), 100, () -> show(m)));
            System.out.printf("  ROM $F000 still %02X%n", m.bus.peek(0xF000));
        }
    }
}
