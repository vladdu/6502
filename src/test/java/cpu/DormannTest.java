package cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import machine.Device;
import org.junit.jupiter.api.Test;
import testdata.TestData;

/** Klaus Dormann's functional and interrupt tests and Bruce Clark's decimal test, each an image run on plain RAM. */
class DormannTest {
    @Test
    void functionalTestReachesTheSuccessLoop() throws IOException {
        assertEquals(0x3469, runUntilSelfLoop(load(TestData.dormannFunctional()), 0x0400), "trap PC");
    }

    @Test
    void decimalTestLeavesErrorClear() throws IOException {
        var ram = load(TestData.dormannDecimal());
        runUntilSelfLoop(ram, 0x0400);
        assertEquals(0, ram.mem[0x000B], "ERROR");
    }

    @Test
    void interruptTestReachesTheSuccessLoop() throws IOException {
        var ram = new Ram();
        byte[] bytes = Files.readAllBytes(TestData.dormannInterrupt());
        for (int i = 0; i < bytes.length; i++) ram.mem[0x000A + i] = bytes[i] & 0xFF;
        var feedback = new Feedback();
        var cpu = new Cpu(new Bus() {
            public int read(int a) { return (a & 0xFFFF) == Feedback.PORT ? feedback.read(0) : ram.read(a); }
            public void write(int a, int v) { if ((a & 0xFFFF) == Feedback.PORT) feedback.write(0, v); else ram.write(a, v); }
            public int peek(int a) { return (a & 0xFFFF) == Feedback.PORT ? feedback.peek(0) : ram.peek(a); }
        });
        cpu.pc = 0x0400;
        for (int before = -1; before != cpu.pc && (feedback.port & 0x80) == 0; ) {
            before = cpu.pc;
            cpu.step();
            cpu.irqLine = feedback.irq(); // as the Machine does after each Step
            cpu.nmiLine = feedback.nmi();
        }
        assertEquals(0, feedback.port & 0x80, "diagnostic stop (bit 7)");
        assertEquals(0x06F5, cpu.pc, "trap PC");
    }

    /** The interrupt test's feedback register at $BFFC: bit 0 drives IRQ, bit 1 NMI (1 asserts), bit 7 is a diagnostic stop. */
    private static final class Feedback implements Device {
        static final int PORT = 0xBFFC;
        int port;

        public int read(int off) { return port; }
        public void write(int off, int v) { port = v; }
        public int peek(int off) { return port; }
        public boolean irq() { return (port & 1) != 0; }
        public boolean nmi() { return (port & 2) != 0; }
    }

    private static Ram load(Path image) throws IOException {
        var ram = new Ram();
        byte[] bytes = Files.readAllBytes(image);
        for (int i = 0; i < bytes.length; i++) ram.mem[i] = bytes[i] & 0xFF;
        return ram;
    }

    /** Steps from {@code start} until an instruction leaves PC unchanged (every trap is {@code jmp *} or a branch to itself). */
    private static int runUntilSelfLoop(Ram ram, int start) {
        var cpu = new Cpu(ram);
        cpu.pc = start;
        for (int before = -1; before != cpu.pc; ) {
            before = cpu.pc;
            cpu.step();
        }
        return cpu.pc;
    }
}
