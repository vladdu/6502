package machine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import asm.Assembler;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskTest {
    static final int COMMAND = 0xD200, STATUS = 0xD201, CONTROL = 0xD202, ERROR = 0xD203,
            SECTOR = 0xD204, PAGE = 0xD206, LAST = 0xD208;

    @TempDir Path dir;
    private final Machine m = new Machine();

    /** A temp image of {@code sectors} Sectors; byte i of Sector n is {@code n + i}. */
    private Path image(int sectors) throws IOException {
        var b = new byte[sectors * 256];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (i / 256 + i % 256);
        Path p = dir.resolve("disk.img");
        Files.write(p, b);
        return p;
    }

    /** Assembles {@code body} at $0200 and loads it in Program mode. */
    private void load(String body) throws Exception {
        String src = ".defsection code, $0200\n.section code\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), _ -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        var out = (Assembler.Output) r;
        m.loadProgram(out.binary(), out.origin());
    }

    /** Issues {@code command} for {@code sector} and {@code page} and waits for done; leaves ERROR in A. */
    private static String transfer(int command, int sector, int page) {
        return String.format("""
                LDA #$%02X
                STA $D204
                LDA #$%02X
                STA $D205
                LDA #$%02X
                STA $D206
                LDA #$%02X
                STA $D200
                BIT $D201
                BPL *-3
                LDA $D203
        """, sector & 0xFF, sector >> 8, page, command);
    }

    @Test
    void readCopiesASectorIntoARamPage() throws Exception {
        m.disk.attach(image(4), false);
        load(transfer(0x01, 2, 0x30) + "STA $D300");

        assertEquals(new StopReason.Exited(0), m.run(Set.of()));
        for (int i = 0; i < 256; i++) assertEquals((2 + i) & 0xFF, m.bus.peek(0x3000 + i), "byte " + i);
        assertEquals(0x80, m.bus.peek(STATUS)); // done, no error
    }

    @Test
    void dmaIntoAWatchedRangeStopsWithTheDiskAsAccessor() throws Exception {
        m.disk.attach(image(4), false);
        load(transfer(0x01, 2, 0x30) + "STA $D300");
        m.watch(List.of(new Watchpoint(0x3005, 2, false, true)));

        assertEquals(new StopReason.Watchpoint(0x3005, true, 0x00, 0x07, "Disk", 1), m.run(Set.of()));
        assertEquals(0x01, m.bus.peek(0x30FF)); // (2 + 255) & $FF: the Step's whole transfer completed
    }

    @Test
    void readModifyWriteRoundTripReachesTheHostFile() throws Exception {
        Path img = image(4);
        m.disk.attach(img, false);
        load(transfer(0x01, 3, 0x40) + """
                INC $4000
                INC $40FF
        """ + transfer(0x02, 3, 0x40) + "STA $D300");

        assertEquals(new StopReason.Exited(0), m.run(Set.of()));
        byte[] file = Files.readAllBytes(img);
        assertEquals(4 * 256, file.length);
        assertEquals(3 + 1, file[3 * 256]);
        assertEquals((byte) (3 + 255 + 1), file[3 * 256 + 255]);
        assertEquals(3 + 1, file[3 * 256 + 1]); // unchanged byte of the same Sector
        assertEquals(2 + 0, file[2 * 256]); // other Sectors untouched
    }

    @Test
    void completesAtTheFirstStepBoundaryWith256CyclesElapsedAfterTheIssuingStep() throws Exception {
        m.disk.attach(image(4), false);
        load("LDA #$01\nSTA $D200\n" + "LDA $00\n".repeat(90)); // LDA zp: 3 cycles
        m.step();
        m.step(); // the issuing Step: its 4 cycles don't count
        assertEquals(0x40, m.bus.peek(STATUS)); // busy
        for (int i = 0; i < 85; i++) m.step(); // 255 cycles
        assertEquals(0x40, m.bus.peek(STATUS));
        m.step(); // 258 >= 256
        assertEquals(0x80, m.bus.peek(STATUS));
        assertEquals(0, m.bus.peek(ERROR));
    }

    /** Runs one command to completion and returns its ERROR code (an undocumented opcode stops, so it reruns). */
    private int errorOf(int command, int sector, int page) throws Exception {
        load(transfer(command, sector, page) + "STA $00\n.byte $02");
        assertInstanceOf(StopReason.Illegal.class, m.run(Set.of()));
        return m.bus.peek(0x0000);
    }

    @Test
    void noMediaStaysOnTheBusAndFailsEveryCommandWithError1() throws Exception {
        assertEquals(0x10, m.bus.peek(STATUS));
        assertEquals(0, m.bus.peek(LAST));
        assertEquals(0, m.bus.peek(LAST + 1));
        assertEquals(1, errorOf(0x01, 0, 0x30));
        assertEquals(0x80 | 0x20 | 0x10, m.bus.peek(STATUS)); // done, error, no media
        assertEquals(1, errorOf(0x02, 0, 0x30));
        assertEquals(1, errorOf(0x07, 0, 0x30));
    }

    @Test
    void lastIsTheLastSectorNumberAndASectorPastItFailsWithError2() throws Exception {
        m.disk.attach(image(0x102), false);
        assertEquals(0x01, m.bus.peek(LAST));
        assertEquals(0x01, m.bus.peek(LAST + 1));
        assertEquals(0, errorOf(0x01, 0x101, 0x30));
        assertEquals(0x01, m.bus.peek(0x3000)); // byte 0 of Sector $101
        assertEquals(2, errorOf(0x01, 0x102, 0x30));
        assertEquals(2, errorOf(0x02, 0xFFFF, 0x30));
    }

    @Test
    void pageOutsideRamFailsWithError3BothWays() throws Exception {
        m.disk.attach(image(1), false);
        assertEquals(0, errorOf(0x01, 0, 0x00));
        assertEquals(0, errorOf(0x01, 0, 0xCF));
        assertEquals(3, errorOf(0x01, 0, 0xD0));
        assertEquals(3, errorOf(0x02, 0, 0xD3)); // WRITE never reads I/O
        assertEquals(3, errorOf(0x01, 0, 0xDF));
        assertEquals(0, errorOf(0x01, 0, 0xE0)); // no ROM: RAM
        assertEquals(0, errorOf(0x01, 0, 0xFF));
    }

    @Test
    void romSpaceIsNotRamWhenAROMIsAttached() throws Exception {
        m.attachRom(m.systemRom, new byte[0x100]);
        m.disk.attach(image(1), false);
        assertEquals(3, errorOf(0x01, 0, 0xE0));
        assertEquals(3, errorOf(0x02, 0, 0xFF));
    }

    @Test
    void writeOnAReadOnlyDiskFailsWithError4AndLeavesTheFile() throws Exception {
        Path img = image(2);
        byte[] before = Files.readAllBytes(img);
        m.disk.attach(img, true);
        assertEquals(0x08, m.bus.peek(STATUS)); // read-only
        assertEquals(0, errorOf(0x01, 1, 0x30));
        assertEquals(4, errorOf(0x02, 1, 0x30));
        assertArrayEquals(before, Files.readAllBytes(img));
    }

    @Test
    void unknownCommandFailsWithError5() throws Exception {
        m.disk.attach(image(1), false);
        assertEquals(5, errorOf(0x00, 0, 0x30));
        assertEquals(5, errorOf(0x03, 0, 0x30));
        assertEquals(5, errorOf(0xFF, 0, 0x30));
    }

    @Test
    void hostFileShrunkUnderTheDiskFailsWithError6() throws Exception {
        Path img = image(4);
        m.disk.attach(img, false);
        Files.write(img, new byte[256]); // the host truncates the image in place
        assertEquals(6, errorOf(0x01, 3, 0x30));
        assertEquals(0x80 | 0x20, m.bus.peek(STATUS));
    }

    @Test
    void registersReadBackAsStoredAndReadsHaveNoSideEffects() throws Exception {
        m.disk.attach(image(1), false);
        m.bus.write(SECTOR, 0x34);
        m.bus.write(SECTOR + 1, 0x12);
        m.bus.write(PAGE, 0x56);
        m.bus.write(CONTROL, 0xFF);
        assertEquals(0x34, m.bus.read(SECTOR));
        assertEquals(0x12, m.bus.read(SECTOR + 1));
        assertEquals(0x56, m.bus.read(PAGE));
        assertEquals(0x80, m.bus.read(CONTROL)); // only bit 7 stored
        for (int off : new int[] {0x00, 0x07, 0x0A, 0xFF}) assertEquals(0xFF, m.bus.read(0xD200 + off), "offset " + off);
        for (int off = 0; off < 0x100; off++) assertEquals(m.bus.read(0xD200 + off), m.bus.peek(0xD200 + off));
    }

    @Test
    void commandWhileBusyIsIgnoredEntirely() throws Exception {
        m.disk.attach(image(4), false);
        load("""
                LDA #$30
                STA $D206
                LDA #$01
                STA $D200
                LDA #$D0
                STA $D206
                LDA #$02
                STA $D200
        """ + "LDA $00\n".repeat(90));
        for (int i = 0; i < 4; i++) m.step(); // through the issuing STA
        for (int i = 0; i < 4 + 81; i++) m.step(); // 6 + 6 + 81 * 3 = 255 cycles
        assertEquals(0x40, m.bus.peek(STATUS));
        m.step(); // 258: the timer was not restarted
        assertEquals(0x80, m.bus.peek(STATUS));
        assertEquals(0, m.bus.peek(ERROR)); // READ to the latched page $30, not WRITE from $D0
        assertEquals(0xD0, m.bus.peek(PAGE));
        assertEquals(0x01, m.bus.peek(0x3001));
    }

    @Test
    void irqIsDoneAndEnableAndAStatusWriteClearsDone() throws Exception {
        m.disk.attach(image(1), false);
        load("""
                CLI
                LDA #$80
                STA $D202
                LDA #$01
                STA $D200
                DEX            ; ~1280 cycles for the IRQ to come
                BNE *-1
                LDA #$01       ; no IRQ
                STA $D300
                .defsection irq, $0300
                .section irq
                LDA $D201
                STA $00
                STA $D201
                LDA $D201
                STA $01
                LDA #$2A
                STA $D300
        """);
        m.bus.write(0xFFFE, 0x00);
        m.bus.write(0xFFFF, 0x03);

        assertEquals(new StopReason.Exited(42), m.run(Set.of()));
        assertEquals(0x80, m.bus.peek(0x00)); // done when the IRQ was taken
        assertEquals(0x00, m.bus.peek(0x01)); // cleared by the STATUS write
        assertFalse(m.cpu.irqLine);
    }

    @Test
    void noIrqWhileDisabled() throws Exception {
        m.disk.attach(image(1), false);
        load("LDA #$01\nSTA $D200\n" + "NOP\n".repeat(200));
        for (int i = 0; i < 2 + 128; i++) m.step();
        assertEquals(0x80, m.bus.peek(STATUS));
        assertFalse(m.cpu.irqLine);
        m.bus.write(CONTROL, 0x80);
        m.step();
        assertTrue(m.cpu.irqLine);
    }

    @Test
    void resetAbortsAnInFlightWriteWithTheFileUntouchedAndKeepsTheImage() throws Exception {
        Path img = image(4);
        byte[] before = Files.readAllBytes(img);
        m.disk.attach(img, false);
        m.bus.write(0x3000, 0x99);
        load(transfer(0x02, 1, 0x30).replace("STA $D200", "STA $D200\nSTA $D202")); // also sets CONTROL bit 7
        for (int i = 0; i < 9; i++) m.step(); // issued, CONTROL set, then waiting
        assertEquals(0x40, m.bus.peek(STATUS));

        m.reset();
        assertEquals(0x00, m.bus.peek(STATUS));
        for (int off : new int[] {0x02, 0x03, 0x04, 0x05, 0x06}) assertEquals(0, m.bus.peek(0xD200 + off), "offset " + off);
        assertEquals(3, m.bus.peek(LAST)); // media survives
        load("NOP\n".repeat(300));
        for (int i = 0; i < 300; i++) m.step();
        assertEquals(0x00, m.bus.peek(STATUS));
        assertArrayEquals(before, Files.readAllBytes(img));
    }

    @Test
    void describeShowsMediaSizeWriteAccessAndBusy() throws Exception {
        assertEquals("Disk: no media", m.disk.state().describe());
        Path p = image(2);
        m.disk.attach(p, false);
        assertEquals("Disk: " + p + ", 512 bytes, read-write, idle", m.disk.state().describe());
        m.bus.write(COMMAND, 0x01);
        assertEquals("Disk: " + p + ", 512 bytes, read-write, busy", m.disk.state().describe());
        m.disk.attach(p, true);
        assertTrue(m.disk.state().describe().endsWith(", read-only, busy"), m.disk.state().describe());
    }

    @Test
    void stateShowsTheImageBusyDoneErrorRegistersAndTheLatchedCommandWithItsElapsedCycles() throws Exception {
        assertEquals(new DeviceState.Disk(null, 0, false, false, false, 0, 0, 0, 0, 0), m.disk.state());
        Path p = image(2);
        m.disk.attach(p, true);
        m.bus.write(SECTOR, 1);
        m.bus.write(PAGE, 0x30);
        m.bus.write(COMMAND, 0x02);
        m.bus.write(SECTOR, 0); // after the command: not what it latched
        m.disk.tick(4); // the issuing Step
        m.disk.tick(100);

        assertEquals(new DeviceState.Disk(p, 2, true, true, false, 0, 0, 0x30, 0x02, 100), m.disk.state());
        m.disk.tick(Disk.DELAY);
        assertEquals(new DeviceState.Disk(p, 2, true, false, true, 4, 0, 0x30, 0x02, 356), m.disk.state()); // WRITE on read-only
    }
    @Test
    void insertSwapsTheImageAndACommandCompletesAgainstTheOneInTheDriveAtCompletion() throws Exception {
        Path first = image(4), second = dir.resolve("second.img");
        Files.write(second, new byte[] {0x5A, 0x5B});
        Files.write(second, new byte[510], StandardOpenOption.APPEND);
        m.disk.attach(first, false);
        load("LDA #$30\nSTA $D206\nLDA #$01\nSTA $D200\n" + "NOP\n".repeat(200));
        for (int i = 0; i < 4; i++) m.step(); // READ Sector 0 issued against the first image

        m.disk.attach(second, true);
        for (int i = 0; i < 130; i++) m.step();

        assertEquals(new DeviceState.Disk(second, 2, true, false, true, 0, 0, 0x30, 0x01, 256), m.disk.state());
        assertEquals(0x5B, m.bus.peek(0x3001)); // from the second image, not the first's 1
        assertEquals(1, m.bus.peek(LAST));
    }

    @Test
    void aRejectedInsertKeepsTheOldImage() throws Exception {
        Path img = image(4);
        m.disk.attach(img, false);
        Path empty = dir.resolve("empty.img");
        Files.write(empty, new byte[0]);

        assertEquals("disk image is empty", assertThrows(LoadException.class, () -> m.disk.attach(empty, true)).getMessage());
        assertThrows(NoSuchFileException.class, () -> m.disk.attach(dir.resolve("missing.img"), false));

        assertEquals(new DeviceState.Disk(img, 4, false, false, false, 0, 0, 0, 0, 0), m.disk.state());
        assertEquals(0, errorOf(0x02, 3, 0x30)); // still writable
    }

    @Test
    void ejectEmptiesTheDriveAndACommandInFlightAcrossItFailsWithError1() throws Exception {
        m.disk.attach(image(4), true);
        load("LDA #$01\nSTA $D200\n" + "NOP\n".repeat(200));
        for (int i = 0; i < 4; i++) m.step();

        m.disk.eject();
        for (int i = 0; i < 130; i++) m.step();

        assertEquals(new DeviceState.Disk(null, 0, false, false, true, 1, 0, 0, 0x01, 256), m.disk.state());
        assertEquals(0x80 | 0x20 | 0x10, m.bus.peek(STATUS)); // done, error, no media
        m.disk.eject(); // ejecting with no Disk image does nothing
        assertNull(m.disk.state().image());
    }

    @Test
    void theWriteProtectTabStopsWritesAndClearingItAllowsThemEvenForAnImageInsertedReadOnly() throws Exception {
        Path img = image(2);
        m.disk.attach(img, true);
        m.disk.writeProtect(false);
        assertFalse(m.disk.state().readOnly());
        m.bus.write(0x3000, 0x99);
        assertEquals(0, errorOf(0x02, 1, 0x30));
        assertEquals((byte) 0x99, Files.readAllBytes(img)[256]);

        m.disk.writeProtect(true);

        assertTrue(m.disk.state().readOnly());
        assertEquals(0x08, m.bus.peek(STATUS) & 0x08);
        assertEquals(4, errorOf(0x02, 0, 0x30));
        assertEquals(0, Files.readAllBytes(img)[0]);
    }

    @Test
    void clearingTheWriteProtectTabIsRefusedWhenTheHostWontOpenTheFileForWriting() throws Exception {
        Path img = image(2);
        assertTrue(img.toFile().setWritable(false));
        assumeFalse(Files.isWritable(img), "running as a user who can write anyway");
        m.disk.attach(img, false);
        assertTrue(m.disk.state().readOnly());

        assertThrows(AccessDeniedException.class, () -> m.disk.writeProtect(false));

        assertTrue(m.disk.state().readOnly());
        assertEquals(0, errorOf(0x01, 1, 0x30)); // the image still reads
    }
}
