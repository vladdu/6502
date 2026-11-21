package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolFile.Symbol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import machine.LoadException;
import machine.Machine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchTest {
    @TempDir Path dir;
    private final Machine m = new Machine();

    private Path write(String name, byte... bytes) throws IOException {
        return Files.write(dir.resolve(name), bytes);
    }

    private Path text(String name, String s) throws IOException {
        return write(name, s.getBytes(UTF_8));
    }

    private Launch program(Path image, int org, Path... syms) {
        return new Launch(m, null, image, org, null, false, List.of(syms));
    }

    @Test
    void programModeLoadsTheImageAtOrgAndReturnsTheSiblingThenTheSymsSymbolsInOrder() throws Exception {
        Path prog = write("prog.bin", (byte) 0xEA);
        Path sibling = text("prog.sym", "0300 start label local\n");
        Path more = text("more.sym", "0400 more label local\n");
        var launch = program(prog, 0x0300, more);

        List<Symbol> symbols = launch.start();

        assertEquals(List.of("start", "more"), symbols.stream().map(Symbol::name).toList());
        assertEquals(0xEA, m.bus.peek(0x0300));
        assertEquals(0x0300, m.cpu.pc);
        assertEquals(List.of(prog + " at $0300"), launch.images());
        assertEquals(List.of(sibling.toString(), more.toString()), launch.symbolFiles());
    }

    @Test
    void romAloneBootsThroughTheResetVector() throws Exception {
        var image = new byte[0x100];
        image[0xFC] = 0x10; // reset -> $FF10
        image[0xFD] = (byte) 0xFF;
        Path rom = write("rom.bin", image);
        var launch = new Launch(m, rom, null, 0x0200, null, false, List.of());

        launch.start();

        assertEquals(0xFF10, m.cpu.pc);
        assertEquals(List.of(rom + " (ROM)"), launch.images());
        assertEquals(List.of(), launch.symbolFiles());
    }

    @Test
    void everyErrorNamesItsFile() throws Exception {
        Path missing = dir.resolve("none.bin");
        var e = assertThrows(LoadException.class, () -> program(missing, 0x0200).start());
        assertTrue(e.getMessage().startsWith("cannot read " + missing + ": "), e.getMessage());

        Path two = write("two.bin", new byte[2]);
        e = assertThrows(LoadException.class, () -> program(two, 0xCFFF).start());
        assertEquals(two + ": load $CFFF-$D000 overlaps the I/O region $D000-$DFFF", e.getMessage());

        Path bad = text("bad.sym", "0200 x nolabel local\n");
        e = assertThrows(LoadException.class, () -> program(two, 0x0200, bad).start());
        assertTrue(e.getMessage().startsWith(bad + ":1: error: "), e.getMessage());
    }

    @Test
    void restartClearsRamAndLoadsTheImageAgain() throws Exception {
        Path prog = write("prog.bin", (byte) 0xEA);
        var launch = program(prog, 0x0200);
        launch.start();
        m.bus.write(0x0300, 0x55);
        m.cpu.pc = 0x0300;

        launch.restart();

        assertEquals(0, m.bus.peek(0x0300));
        assertEquals(0xEA, m.bus.peek(0x0200));
        assertEquals(0x0200, m.cpu.pc);
    }
}
