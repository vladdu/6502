package cli;

import disasm.SymbolFile;
import disasm.SymbolFile.Contents;
import disasm.SymbolFileException;
import disasm.SymbolTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import machine.LoadException;

/** Where an image loads and starts (ADR 0003); {@code symbolFile} is the Symbol file that counts, or null. */
record Placement(int address, int entryPoint, Path symbolFile, Contents contents, List<String> warnings) {
    /** What to do about a mismatched Symbol file where {@code --org} can be given. */
    static final String REMEDY_OR_ORG = "assemble again, remove it, or give --org";
    /** What to do about one where the Monitor's {@code load} takes an address. */
    static final String REMEDY_OR_ADDR = "assemble again, remove it, or give ADDR";
    /** What to do about one where no address can be given. */
    static final String REMEDY_NO_ADDRESS = "assemble again or remove it";
    /** Where an image loads when nothing is typed or recorded. */
    private static final int DEFAULT_ADDRESS = 0x0200;

    /** Places {@code image} by the Symbol file next to it; with no {@code typed} address a mismatched one is refused, saying {@code remedy}. */
    static Placement of(Path image, byte[] bytes, Integer typed, String remedy) throws LoadException, SymbolFileException {
        Path sibling = SymbolFile.sibling(image);
        Path file = Files.exists(sibling) ? sibling : null;
        Contents contents = file == null ? Contents.NONE : SymbolFile.read(file);
        if (contents.imageLine() != null && !contents.imageLine().matches(bytes)) {
            String mismatch = file + " does not match " + image;
            if (typed == null) throw new LoadException(mismatch + ": " + remedy);
            return new Placement(typed, typed, null, Contents.NONE, List.of("warning: " + mismatch + ": ignored"));
        }
        return recorded(image, bytes, typed, file, contents);
    }

    /** Places {@code image} by the {@code file} named for it; one recorded for another image gives only symbols and data ranges. */
    static Placement named(Path image, byte[] bytes, Integer typed, Path file) throws SymbolFileException {
        Contents contents = SymbolFile.read(file);
        if (contents.imageLine() == null || contents.imageLine().matches(bytes)) return recorded(image, bytes, typed, file, contents);
        int address = typed != null ? typed : DEFAULT_ADDRESS;
        return new Placement(address, address, file, contents,
                List.of("warning: " + file + " does not match " + image + ": using only its symbols and data ranges"));
    }

    /** Checks the system ROM's record, which must match and end the image at $FFFF; only the result's Symbol file is used. */
    static Placement ofRom(Path rom, byte[] bytes) throws LoadException, SymbolFileException {
        Placement placed = of(rom, bytes, null, REMEDY_NO_ADDRESS);
        var imageLine = placed.contents().imageLine();
        if (imageLine != null && imageLine.origin() + bytes.length != 0x10000)
            throw new LoadException(String.format("%s: Image origin $%04X and %d bytes end at $%04X; a ROM image must end at $FFFF",
                    rom, imageLine.origin(), bytes.length, imageLine.origin() + bytes.length - 1));
        return placed;
    }

    /** Places {@code image} by what {@code file} records for it, if anything. */
    private static Placement recorded(Path image, byte[] bytes, Integer typed, Path file, Contents contents) throws SymbolFileException {
        var imageLine = contents.imageLine();
        if (imageLine == null) {
            int address = typed != null ? typed : DEFAULT_ADDRESS;
            return new Placement(address, address, file, contents, List.of());
        }
        int origin = imageLine.origin(), offset = imageLine.entryPoint() - origin;
        if (offset < 0 || offset >= bytes.length)
            throw new SymbolFileException(String.format("%s:%d: error: Entry point $%04X is outside the image",
                    file, imageLine.line(), imageLine.entryPoint()));
        int address = typed != null ? typed : origin;
        List<String> warnings = address == origin ? List.of() : List.of(String.format(
                "warning: %s: loaded at $%04X, its Image origin is $%04X; symbols are for $%04X", image, address, origin, origin));
        return new Placement(address, address + offset, file, contents, warnings);
    }

    /** Adds the Symbol file that counts to {@code symbols}. */
    void addSymbolsTo(SymbolTable symbols) {
        if (symbolFile != null) symbols.add(symbolFile, contents);
    }
}
