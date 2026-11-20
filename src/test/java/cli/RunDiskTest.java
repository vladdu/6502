package cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import asm.Assembler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** `run --disk FILE [--disk-ro]`: attach errors, read-only mounts, and a program changing the host file. */
class RunDiskTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.execute(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true), new PrintStream(err, true));
    }

    /** Assembles {@code body} at $0200 into prog.bin. */
    private String prog(String body) throws IOException {
        String src = "*= $0200\n" + body + "\n";
        var r = Assembler.assemble(Path.of("t.s"), p -> src.getBytes(UTF_8));
        if (r instanceof Assembler.Errors(var messages)) fail(String.join("\n", messages));
        Path p = dir.resolve("prog.bin");
        Files.write(p, ((Assembler.Output) r).binary());
        return p.toString();
    }

    /** A sparse image file of {@code size} bytes. */
    private String disk(long size) throws IOException {
        Path p = dir.resolve("disk.img");
        try (var f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(size);
        }
        return p.toString();
    }

    private static final String EXIT_WITH_STATUS = "LDA $D201\nSTA $D300";

    @Test
    void diskRoWithoutDiskIsAUsageError() throws IOException {
        assertEquals(2, run("run", prog("BRK"), "--disk-ro"));
        assertTrue(err.toString().contains("--disk-ro needs --disk"), err.toString());
    }

    @Test
    void programReadsChangesAndWritesASectorOfTheHostFile() throws IOException {
        String img = disk(2 * 256);
        String prog = prog("""
                LDA #$01
                STA $D204
                LDA #$30
                STA $D206
                LDA #$01        ; READ Sector 1 into $3000
                STA $D200
                BIT $D201
                BPL *-3
                LDA #$5A
                STA $3010
                LDA #$02        ; WRITE it back
                STA $D200
                BIT $D201
                BPL *-3
                LDA $D203
                STA $D300
        """);
        assertEquals(0, run("run", prog, "--disk", img));
        byte[] file = Files.readAllBytes(Path.of(img));
        assertEquals(0x5A, file[256 + 0x10]);
        assertEquals(0, file[0x10]);
        assertEquals("", err.toString());
    }

    @Test
    void sixteenMiBIsTheLargestImage() throws IOException {
        // LAST = $FFFF
        assertEquals(0xFF, run("run", prog("LDA $D209\nSTA $D300"), "--disk", disk(256L * 0x10000)));
        assertEquals(125, run("run", prog("NOP"), "--disk", disk(256L * 0x10000 + 256)));
        assertTrue(err.toString().contains("16 MiB"), err.toString());
    }

    @Test
    void emptyImageExits125() throws IOException {
        assertEquals(125, run("run", prog("NOP"), "--disk", disk(0)));
        assertTrue(err.toString().contains("empty"), err.toString());
    }

    @Test
    void imageSizeNotAMultipleOf256Exits125() throws IOException {
        assertEquals(125, run("run", prog("NOP"), "--disk", disk(300)));
        assertTrue(err.toString().contains("multiple of 256"), err.toString());
    }

    @Test
    void missingImageExits125() throws IOException {
        assertEquals(125, run("run", prog("NOP"), "--disk", dir.resolve("nope.img").toString()));
        assertTrue(err.toString().contains("nope.img"), err.toString());
    }

    @Test
    void diskRoMountsReadOnly() throws IOException {
        assertEquals(0x08, run("run", prog(EXIT_WITH_STATUS), "--disk", disk(256), "--disk-ro"));
        assertEquals("", err.toString());
    }

    @Test
    void withoutDiskTheDiskHasNoMedia() throws IOException {
        assertEquals(0x10, run("run", prog(EXIT_WITH_STATUS)));
    }

    @Test
    void unwritableImageMountsReadOnlyWithAWarning() throws IOException {
        String img = disk(256);
        assertTrue(Path.of(img).toFile().setWritable(false));
        assumeFalse(Files.isWritable(Path.of(img)), "running as a user who can write anyway");
        assertEquals(0x08, run("run", prog(EXIT_WITH_STATUS), "--disk", img));
        assertTrue(err.toString().startsWith("6502: warning: "), err.toString());
        assertTrue(err.toString().contains("read-only"), err.toString());
    }
}
