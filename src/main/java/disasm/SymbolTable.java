package disasm;

import disasm.SymbolFile.Data;
import disasm.SymbolFile.Symbol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The symbols of the symbol files added, in load order. Every label names its own address (the first loaded wins a
 * shared address); a name looks up the latest file's value.
 */
public final class SymbolTable {
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Data> data = new ArrayList<>();
    private final Map<String, Integer> values = new HashMap<>();
    private final List<String> files = new ArrayList<>();

    /** Adds a symbol file; an unreadable or malformed one adds nothing. */
    public void add(Path file) throws SymbolFileException {
        SymbolFile.Contents added = SymbolFile.read(file);
        symbols.addAll(added.symbols());
        data.addAll(added.data());
        for (Symbol s : added.symbols()) values.put(s.name(), s.value());
        files.add(file.toString());
    }

    /** Adds {@code image}'s symbol file, {@link SymbolFile#sibling}, when there is one. */
    public void addSiblingOf(Path image) throws SymbolFileException {
        Path sym = SymbolFile.sibling(image);
        if (Files.exists(sym)) add(sym);
    }

    /** The value of {@code name}, or null when no file has it. */
    public Integer value(String name) {
        return values.get(name);
    }

    /** The name of the first label symbol at {@code addr}, or null. */
    public String label(int addr) {
        for (Symbol s : symbols) if (s.label() && s.value() == addr) return s.name();
        return null;
    }

    /** Every symbol in load order, as the Disassembler takes them. */
    public List<Symbol> symbols() {
        return Collections.unmodifiableList(symbols);
    }

    /** Every data range in load order, as the Disassembler takes them. */
    public List<Data> data() {
        return Collections.unmodifiableList(data);
    }

    /** A separate table with the same symbols and files. */
    public SymbolTable copy() {
        var t = new SymbolTable();
        t.symbols.addAll(symbols);
        t.data.addAll(data);
        t.values.putAll(values);
        t.files.addAll(files);
        return t;
    }

    /** The files added, in order. */
    public List<String> files() {
        return Collections.unmodifiableList(files);
    }
}
