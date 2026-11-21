package cpu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CpuTest {
    private final Ram ram = new Ram();
    private final Cpu cpu = new Cpu(ram);

    @Test
    void ldaAbsoluteXAcrossAPageTakesTheExtraCycleAndSetsN() {
        ram.load(0x0200, 0xBD, 0xFF, 0x12); // LDA $12FF,X
        ram.mem[0x1300] = 0x80;
        cpu.pc = 0x0200; cpu.x = 0x01; cpu.p = 0x20;

        assertEquals(5, cpu.step());
        assertEquals(0x80, cpu.a);
        assertEquals(0x0203, cpu.pc);
        assertEquals(0xA0, cpu.p); // N set, Z clear, bit 5 kept
    }

    @Test
    void ldaImmediateZeroSetsZ() {
        ram.load(0x0200, 0xA9, 0x00);
        cpu.pc = 0x0200; cpu.a = 0x55; cpu.p = 0xA0;

        assertEquals(2, cpu.step());
        assertEquals(0x00, cpu.a);
        assertEquals(0x22, cpu.p);
    }

    @Test
    void staAbsoluteXHasNoPageCrossPenalty() {
        ram.load(0x0200, 0x9D, 0xFF, 0x12); // STA $12FF,X
        cpu.pc = 0x0200; cpu.a = 0x42; cpu.x = 0x01; cpu.p = 0x20;

        assertEquals(5, cpu.step());
        assertEquals(0x42, ram.mem[0x1300]);
        assertEquals(0x20, cpu.p);
    }

    @Test
    void ldxInxWrapsToZero() {
        ram.load(0x0200, 0xA2, 0xFF, 0xE8); // LDX #$FF; INX
        cpu.pc = 0x0200; cpu.p = 0x20;

        assertEquals(2, cpu.step());
        assertEquals(0xA0, cpu.p);
        assertEquals(2, cpu.step());
        assertEquals(0x00, cpu.x);
        assertEquals(0x22, cpu.p);
    }

    @Test
    void jmpAbsoluteAndNop() {
        ram.load(0x0200, 0x4C, 0x34, 0x12); // JMP $1234
        ram.load(0x1234, 0xEA);             // NOP
        cpu.pc = 0x0200;

        assertEquals(3, cpu.step());
        assertEquals(0x1234, cpu.pc);
        assertEquals(2, cpu.step());
        assertEquals(0x1235, cpu.pc);
    }

    @Test
    void zeroPageIndexedWrapsWithinPageZero() {
        ram.load(0x0200, 0xB5, 0xFF, 0xB6, 0xFE); // LDA $FF,X; LDX $FE,Y
        ram.mem[0x0001] = 0x11;
        ram.mem[0x0000] = 0x22;
        cpu.pc = 0x0200; cpu.x = 0x02; cpu.y = 0x02;

        assertEquals(4, cpu.step());
        assertEquals(0x11, cpu.a);
        assertEquals(4, cpu.step());
        assertEquals(0x22, cpu.x);
    }

    @Test
    void indexedIndirectAndIndirectIndexed() {
        ram.load(0x0200, 0xA1, 0x10, 0xB1, 0x20); // LDA ($10,X); LDA ($20),Y
        ram.load(0x0014, 0x00, 0x30);              // ($10+4) -> $3000
        ram.mem[0x3000] = 0x33;
        ram.load(0x0020, 0xFF, 0x40);              // ($20) = $40FF, +Y crosses into $4100
        ram.mem[0x4101] = 0x44;
        cpu.pc = 0x0200; cpu.x = 0x04; cpu.y = 0x02;

        assertEquals(6, cpu.step());
        assertEquals(0x33, cpu.a);
        assertEquals(6, cpu.step());
        assertEquals(0x44, cpu.a);
    }

    @Test
    void jmpIndirectReproducesThePageWrapBug() {
        ram.load(0x0200, 0x6C, 0xFF, 0x30); // JMP ($30FF)
        ram.mem[0x30FF] = 0x34;
        ram.mem[0x3000] = 0x12;             // high byte from $3000, not $3100
        ram.mem[0x3100] = 0x99;
        cpu.pc = 0x0200;

        assertEquals(5, cpu.step());
        assertEquals(0x1234, cpu.pc);
    }

    @Test
    void clearPutsRegistersPinsAndTheNmiEdgeBackAsConstructed() {
        ram.load(0xFFFA, 0x00, 0x03); // NMI -> $0300
        cpu.nmiLine = true;
        cpu.step(); // NMI entry: this high level of the pin is now served
        cpu.a = 1; cpu.x = 2; cpu.y = 3; cpu.irqLine = true;

        cpu.clear();

        assertEquals(0, cpu.a); assertEquals(0, cpu.x); assertEquals(0, cpu.y); assertEquals(0, cpu.pc);
        assertEquals(0xFD, cpu.s); assertEquals(Cpu.U | Cpu.I, cpu.p);
        assertFalse(cpu.irqLine); assertFalse(cpu.nmiLine); assertNull(cpu.entry);
        cpu.nmiLine = true; // a high pin on a cleared CPU is a fresh edge
        assertEquals(7, cpu.step());
        assertEquals("NMI", cpu.entry);
    }

    @Test
    void undocumentedOpcodeThrowsWithPcOnTheOpcodeAndNoStateChanged() {
        ram.load(0x0200, 0xDB);
        cpu.pc = 0x0200; cpu.a = 1; cpu.x = 2; cpu.y = 3; cpu.s = 0xFD; cpu.p = 0x24;

        var e = assertThrows(IllegalOpcode.class, cpu::step);
        assertEquals(0x0200, e.pc);
        assertEquals(0xDB, e.opcode);
        assertEquals(0x0200, cpu.pc);
        assertEquals(1, cpu.a); assertEquals(2, cpu.x); assertEquals(3, cpu.y);
        assertEquals(0xFD, cpu.s); assertEquals(0x24, cpu.p);
    }
}
