package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import asm.Assembler;
import disasm.SymbolFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import machine.Machine;
import org.junit.jupiter.api.Test;

class DefsCommandTest {
    @Test
    void printsMachineInc() {
        var out = new ByteArrayOutputStream();
        assertEquals(0, Main.execute(new String[] {"defs"}, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, UTF_8), System.err));
        assertEquals(DefsCommand.text(), out.toString(UTF_8));
    }

    @Test
    void machineIncAssemblesAndItsBasesMatchTheMemoryMap() throws Exception {
        var r = Assembler.assemble(Path.of("t.s"), p -> (p.toString().equals("t.s") ? ".include \"machine.inc\"\n" : DefsCommand.text()).getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var values = new HashMap<String, Integer>();
        for (var s : SymbolFile.parse("t.sym", ((Assembler.Output) r).symbols()).symbols()) values.put(s.name(), s.value());

        Map<String, String> names = Map.of("RAM", "RAM", "Character ROM", "CHAR_ROM", "Console", "CON", "Timer", "TIMER",
                "Disk", "DISK", "Exit device", "EXIT", "system ROM", "SYS_ROM");
        for (Machine.Area a : new Machine().areas()) {
            if (a.kind() == Machine.Area.Kind.UNMAPPED) continue;
            assertTrue(names.containsKey(a.name()), "machine.inc has no constant for area " + a.name());
            assertEquals(a.base(), values.get(names.get(a.name())), a.name());
        }
    }
}
