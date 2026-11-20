package cpu;

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
            case "STA" -> bus.write(address(op.mode()), a);
            case "INX" -> x = nz(x + 1);
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
