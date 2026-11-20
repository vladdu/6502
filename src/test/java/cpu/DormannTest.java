package cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import testdata.TestData;

/** Klaus Dormann's functional test and Bruce Clark's decimal test, each a 64K image run on plain RAM. */
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
