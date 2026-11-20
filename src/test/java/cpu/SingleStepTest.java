package cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import testdata.Json;
import testdata.TestData;

/** Tom Harte's SingleStepTests: one dynamic test per opcode file, final state plus cycle count. */
@Tag("singlestep")
class SingleStepTest {
    @TestFactory
    Stream<DynamicTest> opcodes() {
        return IntStream.range(0, 256)
                .filter(op -> Opcodes.get(op) != null)
                .mapToObj(op -> DynamicTest.dynamicTest(String.format("$%02X %s %s", op, Opcodes.get(op).mnemonic(), Opcodes.get(op).mode()),
                        () -> runFile(op)));
    }

    @SuppressWarnings("unchecked")
    private static void runFile(int op) throws IOException {
        var tests = (List<Map<String, Object>>) Json.parse(Files.readString(TestData.singleStep(op)));
        var ram = new Ram();
        var cpu = new Cpu(ram);
        for (var t : tests) {
            var initial = (Map<String, Object>) t.get("initial");
            var fin = (Map<String, Object>) t.get("final");
            cpu.pc = reg(initial, "pc"); cpu.s = reg(initial, "s"); cpu.a = reg(initial, "a");
            cpu.x = reg(initial, "x"); cpu.y = reg(initial, "y"); cpu.p = reg(initial, "p");
            for (var cell : cells(initial)) ram.mem[cell.get(0)] = cell.get(1);

            int cycles = cpu.step();

            assertEquals(state(fin, ((List<?>) t.get("cycles")).size(), cells(fin)),
                    String.format("pc=%04X s=%02X a=%02X x=%02X y=%02X p=%02X cycles=%d ram=%s",
                            cpu.pc, cpu.s, cpu.a, cpu.x, cpu.y, cpu.p, cycles, ramString(ram, cells(fin))),
                    () -> (String) t.get("name"));

            for (var cell : cells(initial)) ram.mem[cell.get(0)] = 0;
            for (var cell : cells(fin)) ram.mem[cell.get(0)] = 0;
        }
    }

    private static String state(Map<String, Object> s, int cycles, List<List<Integer>> ram) {
        var expected = new StringBuilder();
        for (var cell : ram) expected.append(String.format("%04X:%02X ", cell.get(0), cell.get(1)));
        return String.format("pc=%04X s=%02X a=%02X x=%02X y=%02X p=%02X cycles=%d ram=%s",
                reg(s, "pc"), reg(s, "s"), reg(s, "a"), reg(s, "x"), reg(s, "y"), reg(s, "p"), cycles, expected);
    }

    private static String ramString(Ram ram, List<List<Integer>> cells) {
        var actual = new StringBuilder();
        for (var cell : cells) actual.append(String.format("%04X:%02X ", cell.get(0), ram.mem[cell.get(0)]));
        return actual.toString();
    }

    private static int reg(Map<String, Object> s, String name) {
        return (Integer) s.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Integer>> cells(Map<String, Object> s) {
        return (List<List<Integer>>) s.get("ram");
    }
}
