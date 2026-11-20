package cpu;

import java.util.function.IntUnaryOperator;

/** The NMOS 6502, instruction-stepped over a {@link Bus}. Registers are plain fields. */
public final class Cpu {
    public static final int C = 0x01, Z = 0x02, I = 0x04, D = 0x08, B = 0x10, U = 0x20, V = 0x40, N = 0x80;

    /** P is stored with bit 5 (U) always set and B never set. */
    public int a, x, y, s = 0xFD, pc, p = U | I;
    /** Input pins, set by the Machine after each Step. */
    public boolean irqLine, nmiLine;

    private final Bus bus;
    private boolean crossed;

    public Cpu(Bus bus) {
        this.bus = bus;
    }

    /** Reset sequence: PC from the reset vector at $FFFC, S=$FD, I set. */
    public void reset() {
        pc = bus.read(0xFFFC) | bus.read(0xFFFD) << 8;
        s = 0xFD;
        p = U | I;
    }

    /** Executes one Step and returns its cycle count. */
    public int step() {
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
            case "JMP" -> pc = address(op.mode());
            case "NOP" -> {}
            default -> {
                // shortcut: documented opcodes outside the tracer subset report as illegal, until the full CPU lands
                pc = at;
                throw new IllegalOpcode(at, code);
            }
        }
        return op.cycles() + (op.pageCross() && crossed ? 1 : 0);
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
