package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import disasm.SymbolFile.Symbol;
import disasm.SymbolTable;
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
        return new Launch(m, null, null, image, org, null, false, List.of(syms));
    }

    @Test
    void programModeLoadsTheImageAtOrgAndReturnsTheSiblingThenTheSymsSymbolsInOrder() throws Exception {
        Path prog = write("prog.bin", (byte) 0xEA);
        Path sibling = text("prog.sym", "0300 start label local\n");
        Path more = text("more.sym", "0400 more label local\n");
        var launch = program(prog, 0x0300, more);

        SymbolTable symbols = launch.start();

        assertEquals(List.of("start", "more"), symbols.symbols().stream().map(Symbol::name).toList());
        assertEquals(0xEA, m.bus.peek(0x0300));
        assertEquals(0x0300, m.cpu.pc);
        assertEquals(List.of(prog + " at $0300"), launch.images());
        assertEquals(List.of(sibling.toString(), more.toString()), symbols.files());
    }

    @Test
    void romAloneBootsThroughTheResetVector() throws Exception {
        var image = new byte[0x100];
        image[0xFC] = 0x10; // reset -> $FF10
        image[0xFD] = (byte) 0xFF;
        Path rom = write("rom.bin", image);
        var launch = new Launch(m, rom, null, null, 0x0200, null, false, List.of());

        SymbolTable symbols = launch.start();

        assertEquals(0xFF10, m.cpu.pc);
        assertEquals(List.of(rom + " (ROM)"), launch.images());
        assertEquals(List.of(), symbols.files());
    }

    @Test
    void theCharacterRomIsAttachedAtC800AndRefusesAProgramLoadedOverIt() throws Exception {
        Path glyphs = write("glyphs.bin", (byte) 0x3C, (byte) 0x66);
        Path prog = write("prog.bin", (byte) 0xEA);
        var launch = new Launch(m, null, glyphs, prog, 0x0200, null, false, List.of());

        launch.start();

        assertEquals(0x3C, m.bus.peek(0xC800));
        assertEquals(0x66, m.bus.peek(0xC801));
        assertEquals(List.of(glyphs + " (Character ROM)", prog + " at $0200"), launch.images());
        var over = new Launch(new Machine(), null, glyphs, prog, 0xC800, null, false, List.of());
        var e = assertThrows(LoadException.class, over::start);
        assertEquals(prog + ": load $C800-$C800 overlaps $C800-$CFFF, which is not RAM", e.getMessage());
    }

    @Test
    void restartRereadsTheCharacterRom() throws Exception {
        Path glyphs = write("glyphs.bin", (byte) 0x3C, (byte) 0x66);
        Path prog = write("prog.bin", (byte) 0xEA);
        var launch = new Launch(m, null, glyphs, prog, 0x0200, null, false, List.of());
        launch.start();
        write("glyphs.bin", (byte) 0x18);

        launch.restart();

        assertEquals(0x18, m.bus.peek(0xC800));
        assertEquals(0xFF, m.bus.peek(0xC801)); // the shorter image no longer covers it
        m.bus.write(0xC800, 0x00);
        assertEquals(0x18, m.bus.peek(0xC800));
        assertEquals(List.of(glyphs + " (Character ROM)", prog + " at $0200"), launch.images());
    }

    @Test
    void everyErrorNamesItsFile() throws Exception {
        Path missing = dir.resolve("none.bin");
        var e = assertThrows(LoadException.class, () -> program(missing, 0x0200).start());
        assertTrue(e.getMessage().startsWith("cannot read " + missing + ": "), e.getMessage());

        Path two = write("two.bin", new byte[2]);
        e = assertThrows(LoadException.class, () -> program(two, 0xCFFF).start());
        assertEquals(two + ": load $CFFF-$D000 overlaps $D000-$D0FF, which is not RAM", e.getMessage());

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

    @Test
    void restartRereadsTheSymbolFilesIncludingASiblingThatAppearedSinceTheLaunch() throws Exception {
        Path prog = write("prog.bin", (byte) 0xEA);
        var launch = program(prog, 0x0200);
        assertEquals(List.of(), launch.start().symbols());
        Path sibling = text("prog.sym", "0200 start label local\n");

        SymbolTable symbols = launch.restart();

        assertEquals(List.of("start"), symbols.symbols().stream().map(Symbol::name).toList());
        assertEquals(List.of(sibling.toString()), symbols.files());
    }

    @Test
    void aRestartThatCannotReadAFileLeavesTheMachineAsItWas() throws Exception {
        Path prog = write("prog.bin", (byte) 0xEA);
        var launch = program(prog, 0x0200);
        launch.start();
        m.bus.write(0x0300, 0x55);
        Files.delete(prog);

        var e = assertThrows(LoadException.class, launch::restart);

        assertTrue(e.getMessage().startsWith("cannot read " + prog + ": "), e.getMessage());
        assertEquals(0x55, m.bus.peek(0x0300));
    }
}
