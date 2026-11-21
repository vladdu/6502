package disasm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Assembler's symbol file, {@code <hex value> <name> <label|const> <global|local>} per line with
 * {@code ;} comments; read by the Disassembler and the Monitor.
 */
public final class SymbolFile {
    /** One symbol line; {@code label} false for a const. Cheap local labels are named {@code parent@name}. */
    public record Symbol(int value, String name, boolean label) {}

    private SymbolFile() {}

    /** {@code prog.bin} -> {@code prog.sym}, next to it: where the Assembler writes an image's symbol file. */
    public static Path sibling(Path image) {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".sym");
    }

    /** {@link #parse} of the file's text; a read failure fails with {@code cannot read file: cause}. */
    public static List<Symbol> read(Path file) throws SymbolFileException {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new SymbolFileException("cannot read " + file + ": " + e);
        }
        return parse(file.toString(), text);
    }

    /**
     * Symbols in file order. A line whose first word is not hex is of an unknown kind and skipped, so
     * new kinds can be added; a malformed symbol line fails with {@code file:line: error: msg}.
     */
    public static List<Symbol> parse(String file, String text) throws SymbolFileException {
        var out = new ArrayList<Symbol>();
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int semi = line.indexOf(';');
            String[] w = (semi < 0 ? line : line.substring(0, semi)).trim().split("\\s+");
            if (w[0].isEmpty() || !w[0].matches("[0-9A-Fa-f]+")) continue;
            String where = file + ":" + (n + 1) + ": error: ";
            if (w.length != 4) throw new SymbolFileException(where + "expected '<hex value> <name> <label|const> <global|local>'");
            if (w[0].length() > 8) throw new SymbolFileException(where + "value " + w[0] + " is longer than 8 hex digits");
            if (!Set.of("label", "const").contains(w[2])) throw new SymbolFileException(where + "expected label or const, not '" + w[2] + "'");
            if (!Set.of("global", "local").contains(w[3])) throw new SymbolFileException(where + "expected global or local, not '" + w[3] + "'");
            out.add(new Symbol((int) Long.parseLong(w[0], 16), w[1], w[2].equals("label")));
        }
        return out;
    }
}
