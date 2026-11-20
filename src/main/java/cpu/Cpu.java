package cpu;

import java.util.function.IntUnaryOperator;

/** The NMOS 6502, instruction-stepped over a {@link Bus}. Registers are plain fields. */
public final class Cpu {
    public static final int C = 0x01, Z = 0x02, I = 0x04, D = 0x08, B = 0x10, U = 0x20, V = 0x40, N = 0x80;

    /** P is stored with bit 5 (U) always set and B never set. */
    public int a, x, y, s = 0xFD, pc, p = U | I;
    /** Input pins, set by the Machine after each Step. */
    public boolean irqLine, nmiLine;
    /** The interrupt entry the last Step made: "IRQ", "NMI" or "BRK"; null when it ran another instruction. */
    public String entry;

    private final Bus bus;
    private boolean crossed;
    /** NMI is edge-triggered: set once the current high level of the pin has been entered. */
    private boolean nmiServed;

    public Cpu(Bus bus) {
        this.bus = bus;
    }

    /** Reset sequence: PC from the reset vector at $FFFC, S=$FD, I set. */
    public void reset() {
        pc = bus.read(0xFFFC) | bus.read(0xFFFD) << 8;
        s = 0xFD;
        p = U | I;
    }

    /**
     * Executes one Step and returns its cycle count. The pins are sampled first: an NMI on a rising
     * edge, else an IRQ while I is clear, makes this Step an interrupt entry of 7 cycles.
     */
    public int step() {
        entry = null;
        if (!nmiLine) nmiServed = false;
        else if (!nmiServed) {
            enter(0xFFFA, pc, 0);
            nmiServed = true;
            entry = "NMI";
            return 7;
        }
        if (irqLine && (p & I) == 0) {
            enter(0xFFFE, pc, 0);
            entry = "IRQ";
            return 7;
        }
        int at = pc, code = bus.read(pc);
        Opcode op = Opcodes.get(code);
        if (op == null) throw new IllegalOpcode(at, code);
        pc = (pc + 1) & 0xFFFF;
        crossed = false;
        switch (op.mnemonic()) {
            case "LDA" -> a = nz(bus.read(address(op.mode())));
            case "LDX" -> x = nz(bus.read(address(op.mode())));
            case "LDY" -> y = nz(bus.read(address(op.mode())));
            case "STA" -> bus.write(address(op.mode()), a);
            case "STX" -> bus.write(address(op.mode()), x);
            case "STY" -> bus.write(address(op.mode()), y);
            case "TAX" -> x = nz(a);
            case "TAY" -> y = nz(a);
            case "TXA" -> a = nz(x);
            case "TYA" -> a = nz(y);
            case "TSX" -> x = nz(s);
            case "TXS" -> s = x;
            case "CLC" -> p &= ~C;
            case "SEC" -> p |= C;
            case "CLI" -> p &= ~I;
            case "SEI" -> p |= I;
            case "CLV" -> p &= ~V;
            case "CLD" -> p &= ~D;
            case "SED" -> p |= D;
            case "INC" -> modify(op.mode(), v -> nz(v + 1));
            case "DEC" -> modify(op.mode(), v -> nz(v - 1));
            case "INX" -> x = nz(x + 1);
            case "INY" -> y = nz(y + 1);
            case "DEX" -> x = nz(x - 1);
            case "DEY" -> y = nz(y - 1);
            case "AND" -> a = nz(a & bus.read(address(op.mode())));
            case "ORA" -> a = nz(a | bus.read(address(op.mode())));
            case "EOR" -> a = nz(a ^ bus.read(address(op.mode())));
            case "BIT" -> {
                int m = bus.read(address(op.mode()));
                p = (p & ~(N | V | Z)) | (m & (N | V)) | ((a & m) == 0 ? Z : 0);
            }
            case "ASL" -> modify(op.mode(), v -> nz(carry(v >> 7, v << 1)));
            case "LSR" -> modify(op.mode(), v -> nz(carry(v, v >> 1)));
            case "ROL" -> modify(op.mode(), v -> nz(carry(v >> 7, v << 1 | p & C)));
            case "ROR" -> modify(op.mode(), v -> nz(carry(v, v >> 1 | (p & C) << 7)));
            case "CMP" -> compare(a, op.mode());
            case "CPX" -> compare(x, op.mode());
            case "CPY" -> compare(y, op.mode());
            case "ADC" -> adc(bus.read(address(op.mode())));
            case "SBC" -> sbc(bus.read(address(op.mode())));
            case "BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ" -> {
                // bits 7-6 pick the flag (N V C Z), bit 5 the value that takes the branch
                int flag = new int[] {N, V, C, Z}[code >> 6];
                return branch(((p & flag) != 0) == ((code & 0x20) != 0));
            }
            case "JMP" -> pc = address(op.mode());
            case "JSR" -> {
                // the high operand byte is read after the pushes, as on the real chip
                int lo = fetch();
                push(pc >> 8);
                push(pc);
                pc = lo | bus.read(pc) << 8;
            }
            case "RTS" -> pc = (pull() | pull() << 8) + 1 & 0xFFFF;
            case "BRK" -> {
                pc = at; // left on the BRK if the vector is stray
                enter(0xFFFE, (at + 2) & 0xFFFF, B); // return past the padding byte
                entry = "BRK";
            }
            case "RTI" -> {
                p = pull() & ~B | U;
                pc = pull() | pull() << 8;
            }
            case "PHA" -> push(a);
            case "PHP" -> push(p | B | U);
            case "PLA" -> a = nz(pull());
            case "PLP" -> p = pull() & ~B | U;
            case "NOP" -> {}
            default -> throw new AssertionError(op);
        }
        return op.cycles() + (op.pageCross() && crossed ? 1 : 0);
    }

    /** Taken: +1 cycle, +1 more when the target is on another page than the next instruction. */
    private int branch(boolean taken) {
        int offset = (byte) fetch();
        if (!taken) return 2;
        int target = (pc + offset) & 0xFFFF;
        int cycles = (target & 0xFF00) == (pc & 0xFF00) ? 3 : 4;
        pc = target;
        return cycles;
    }

    /** NMOS ADC. Decimal mode per Bruce Clark: Z from the binary sum, N and V before the high-nibble fix-up. */
    private void adc(int m) {
        int c = p & C;
        int bin = a + m + c;
        if ((p & D) == 0) {
            p = (p & ~(C | V)) | (bin > 0xFF ? C : 0) | (~(a ^ m) & (a ^ bin) & 0x80) >> 1;
            a = nz(bin);
            return;
        }
        int lo = (a & 0x0F) + (m & 0x0F) + c;
        if (lo >= 0x0A) lo = ((lo + 0x06) & 0x0F) + 0x10;
        int t = (a & 0xF0) + (m & 0xF0) + lo;
        p = (p & ~(N | V | Z | C)) | (t & N) | (~(a ^ m) & (a ^ t) & 0x80) >> 1 | ((bin & 0xFF) == 0 ? Z : 0);
        if (t >= 0xA0) t += 0x60;
        if (t > 0xFF) p |= C;
        a = t & 0xFF;
    }

    /** NMOS SBC. In decimal mode the flags still come from the binary difference; only A is BCD-adjusted. */
    private void sbc(int m) {
        if ((p & D) == 0) { adc(m ^ 0xFF); return; }
        int c = p & C, bin = a - m - (1 - c);
        p = (p & ~(N | V | Z | C)) | (bin >= 0 ? C : 0) | ((a ^ m) & (a ^ bin) & 0x80) >> 1;
        nz(bin);
        int lo = (a & 0x0F) - (m & 0x0F) + c - 1;
        if (lo < 0) lo = ((lo - 0x06) & 0x0F) - 0x10;
        int t = (a & 0xF0) - (m & 0xF0) + lo;
        if (t < 0) t -= 0x60;
        a = t & 0xFF;
    }

    /**
     * Interrupt entry: push {@code ret} and P ({@code b} is B in the pushed copy), set I, PC from the
     * vector. A $0000 vector throws {@link StrayInterrupt} before anything changes.
     */
    private void enter(int vector, int ret, int b) {
        int target = bus.read(vector) | bus.read(vector + 1) << 8;
        if (target == 0) throw new StrayInterrupt(vector);
        push(ret >> 8);
        push(ret);
        push(p | b | U);
        p |= I;
        pc = target;
    }

    private void push(int v) {
        bus.write(0x100 | s, v & 0xFF);
        s = (s - 1) & 0xFF;
    }

    private int pull() {
        s = (s + 1) & 0xFF;
        return bus.read(0x100 | s);
    }

    /** Read-modify-write on A (ACC mode) or memory; one read, one write. */
    private void modify(Mode mode, IntUnaryOperator f) {
        if (mode == Mode.ACC) { a = f.applyAsInt(a); return; }
        int ea = address(mode);
        bus.write(ea, f.applyAsInt(bus.read(ea)));
    }

    /** Sets C from bit 0 of {@code bit}, returns {@code v}. */
    private int carry(int bit, int v) {
        p = (p & ~C) | (bit & 1);
        return v;
    }

    private void compare(int r, Mode mode) {
        int m = bus.read(address(mode));
        p = (p & ~C) | (r >= m ? C : 0);
        nz(r - m);
    }

    /** Effective address for a memory-operand mode; consumes the operand bytes. */
    private int address(Mode mode) {
        return switch (mode) {
            case IMM -> { int ea = pc; pc = (pc + 1) & 0xFFFF; yield ea; }
            case ZP -> fetch();
            case ZPX -> (fetch() + x) & 0xFF;
            case ZPY -> (fetch() + y) & 0xFF;
            case ABS -> fetchWord();
            case ABX -> indexed(fetchWord(), x);
            case ABY -> indexed(fetchWord(), y);
            case IND -> { int ptr = fetchWord(); yield bus.read(ptr) | bus.read((ptr & 0xFF00) | ((ptr + 1) & 0xFF)) << 8; } // page-wrap bug
            case IZX -> zpWord((fetch() + x) & 0xFF);
            case IZY -> indexed(zpWord(fetch()), y);
            default -> throw new IllegalArgumentException("no memory operand: " + mode);
        };
    }

    private int indexed(int base, int index) {
        int ea = (base + index) & 0xFFFF;
        crossed = (base & 0xFF00) != (ea & 0xFF00);
        return ea;
    }

    private int zpWord(int zp) {
        return bus.read(zp) | bus.read((zp + 1) & 0xFF) << 8;
    }

    private int fetch() {
        int v = bus.read(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetchWord() {
        return fetch() | fetch() << 8;
    }

    private int nz(int v) {
        v &= 0xFF;
        p = (p & ~(N | Z)) | (v & N) | (v == 0 ? Z : 0);
        return v;
    }
}
