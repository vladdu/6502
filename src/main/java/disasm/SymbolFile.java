package disasm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * The Assembler's symbol file, {@code <hex value> <name> <label|const> <global|local>},
 * {@code data <hex start> <hex length>} or {@code image <hex origin> <hex Entry point> <hex CRC-32>} per line with
 * {@code ;} comments; read by the Simulator and the Disassembler (ADR 0003).
 */
public final class SymbolFile {
    /** One symbol line; {@code label} false for a const. Cheap local labels are named {@code parent@name}. */
    public record Symbol(int value, String name, boolean label) {}

    /** {@code length} bytes from {@code start} that are data, not code; may wrap past $FFFF. */
    public record Data(int start, int length) {
        /** Whether {@code addr} (taken mod $10000) is in the range. */
        public boolean contains(int addr) {
            return (addr - start & 0xFFFF) < length;
        }
    }

    /** The {@code image} line, on line {@code line}: the Image origin, Entry point and CRC-32 of the image it was written for. */
    public record ImageLine(int origin, int entryPoint, int crc, int line) {
        /** Whether {@code bytes} are that image, by their checksum. */
        public boolean matches(byte[] bytes) {
            var sum = new CRC32();
            sum.update(bytes);
            return (int) sum.getValue() == crc;
        }
    }

    /** A file's symbols and data ranges, each in file order, and its {@code image} line or null. */
    public record Contents(List<Symbol> symbols, List<Data> data, ImageLine imageLine) {
        /** What no Symbol file gives. */
        public static final Contents NONE = new Contents(List.of(), List.of(), null);
    }

    private SymbolFile() {}

    /** {@code prog.bin} -> {@code prog.sym}, next to it: where the Assembler writes an image's symbol file. */
    public static Path sibling(Path image) {
        String name = Objects.requireNonNull(image.getFileName(), "image path has no file name").toString();
        int dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".sym");
    }

    /** {@link #parse} of the file's text; a read failure fails with {@code cannot read file: cause}. */
    public static Contents read(Path file) throws SymbolFileException {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new SymbolFileException("cannot read " + file + ": " + e);
        }
        return parse(file.toString(), text);
    }

    /**
     * Symbols and data ranges in file order, and the image line. A line whose first word is neither hex, {@code data}
     * nor {@code image} is of an unknown kind and skipped, so new kinds can be added; a malformed line fails with
     * {@code file:line: error: msg}.
     */
    public static Contents parse(String file, String text) throws SymbolFileException {
        var out = new ArrayList<Symbol>();
        var data = new ArrayList<Data>();
        ImageLine imageLine = null;
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int semi = line.indexOf(';');
            String[] w = (semi < 0 ? line : line.substring(0, semi)).trim().split("\\s+", -1); // input is trimmed, so -1 changes nothing; it just keeps split explicit
            String where = file + ":" + (n + 1) + ": error: ";
            if (w[0].equals("data")) {
                if (w.length != 3 || !w[1].matches("[0-9A-Fa-f]{1,4}") || !w[2].matches("[0-9A-Fa-f]{1,5}"))
                    throw new SymbolFileException(where + "expected 'data <hex start> <hex length>'");
                int length = Integer.parseInt(w[2], 16);
                if (length < 1 || length > 0x10000) throw new SymbolFileException(where + "data length " + w[2] + " is not 1 to 10000");
                data.add(new Data(Integer.parseInt(w[1], 16), length));
                continue;
            }
            if (w[0].equals("image")) {
                if (w.length != 4 || !w[1].matches("[0-9A-Fa-f]{1,4}") || !w[2].matches("[0-9A-Fa-f]{1,4}") || !w[3].matches("[0-9A-Fa-f]{8}"))
                    throw new SymbolFileException(where + "expected 'image <hex origin> <hex Entry point> <8 hex digit CRC-32>'");
                if (imageLine != null) throw new SymbolFileException(where + "a second image line");
                imageLine = new ImageLine(Integer.parseInt(w[1], 16), Integer.parseInt(w[2], 16), Integer.parseUnsignedInt(w[3], 16), n + 1);
                continue;
            }
            if (w[0].isEmpty() || !w[0].matches("[0-9A-Fa-f]+")) continue;
            if (w.length != 4) throw new SymbolFileException(where + "expected '<hex value> <name> <label|const> <global|local>'");
            if (w[0].length() > 8) throw new SymbolFileException(where + "value " + w[0] + " is longer than 8 hex digits");
            if (!Set.of("label", "const").contains(w[2])) throw new SymbolFileException(where + "expected label or const, not '" + w[2] + "'");
            if (!Set.of("global", "local").contains(w[3])) throw new SymbolFileException(where + "expected global or local, not '" + w[3] + "'");
            out.add(new Symbol((int) Long.parseLong(w[0], 16), w[1], w[2].equals("label")));
        }
        return new Contents(out, data, imageLine);
    }
}
