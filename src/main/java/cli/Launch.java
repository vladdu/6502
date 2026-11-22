package cli;

import disasm.SymbolFileException;
import disasm.SymbolTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import machine.LoadException;
import machine.Machine;

/**
 * How the Simulator was told to start a Machine: the ROMs, the image and any load address typed for it, the Disk and
 * the symbol files that go with them. Every error is a LoadException whose message names the file it is about.
 */
record Launch(Machine machine, Path rom, Path charRom, Path image, Integer org, Path disk, boolean diskReadOnly, List<Path> syms) {
    /** What a start or Restart came to: the Symbol table, the images as {@code info} shows them, and warnings to show. */
    record Started(SymbolTable symbols, List<String> images, List<String> warnings) {}

    /** A null {@code rom}, {@code charRom}, {@code image}, {@code org} or {@code disk} is left out; with no image the ROM boots. */
    Launch {
        syms = List.copyOf(syms);
    }

    /**
     * Attaches the Disk and the ROMs, then loads the image in Program mode or ROM boots. The Symbol table has the
     * ROM's and the image's .sym next to them (when there), then {@code syms}, in that order.
     */
    Started start() throws LoadException {
        if (disk != null) about(disk, () -> machine.disk.attach(disk, diskReadOnly));
        return boot(false);
    }

    /**
     * Restart: the same start on the cleared Machine, every image and symbol file read afresh; the Disk stays attached.
     * Every file is read and placed before the Machine is cleared, so a refused Restart leaves it as it was.
     */
    Started restart() throws LoadException {
        return boot(true);
    }

    /** The images as {@code info} shows them, the program's at {@code program}. */
    private List<String> images(Placement program) {
        var images = new ArrayList<String>();
        if (rom != null) images.add(rom + " (ROM)");
        if (charRom != null) images.add(charRom + " (Character ROM)");
        if (program != null) {
            String shown = String.format("%s at $%04X", image, program.address());
            if (program.entryPoint() != program.address()) shown += String.format(" (Entry point $%04X)", program.entryPoint());
            images.add(shown);
        }
        return images;
    }

    private Started boot(boolean clear) throws LoadException {
        byte[] romBytes = rom == null ? null : read(rom), charBytes = charRom == null ? null : read(charRom),
                imageBytes = image == null ? null : read(image);
        var symbols = new SymbolTable();
        Placement system, program;
        try {
            system = rom == null ? null : Placement.ofRom(rom, romBytes);
            program = image == null ? null : Placement.of(image, imageBytes, org, clear ? Placement.REMEDY_NO_ADDRESS : Placement.REMEDY_OR_ORG);
        } catch (SymbolFileException e) {
            throw new LoadException(e.getMessage()); // already names the file and line
        }
        if (system != null) system.addSymbolsTo(symbols);
        if (program != null) program.addSymbolsTo(symbols);
        for (Path p : syms) about(p, () -> symbols.add(p));
        if (clear) {
            place(new Machine(), romBytes, charBytes, imageBytes, program); // refuses what the cleared Machine would
            machine.clearForRestart();
        }
        place(machine, romBytes, charBytes, imageBytes, program);
        return new Started(symbols, images(program), program == null ? List.of() : program.warnings());
    }

    /** Attaches the ROMs to {@code to}, then loads the image in Program mode or ROM boots. */
    private void place(Machine to, byte[] romBytes, byte[] charBytes, byte[] imageBytes, Placement program) throws LoadException {
        if (romBytes != null) about(rom, () -> to.attachRom(to.systemRom, romBytes));
        if (charBytes != null) about(charRom, () -> to.attachRom(to.charRom, charBytes));
        if (program != null) about(image, () -> to.loadProgram(imageBytes, program.address(), program.entryPoint()));
        else about(rom, to::bootRom);
    }

    private static byte[] read(Path file) throws LoadException {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
    }

    private static LoadException cannotRead(Path file, IOException e) {
        return new LoadException("cannot read " + file + ": " + e);
    }

    private interface Action {
        void run() throws IOException, LoadException, SymbolFileException;
    }

    /** Runs {@code action}, naming {@code file} in a read or load error. */
    private static void about(Path file, Action action) throws LoadException {
        try {
            action.run();
        } catch (IOException e) {
            throw cannotRead(file, e);
        } catch (LoadException e) {
            throw new LoadException(file + ": " + e.getMessage());
        } catch (SymbolFileException e) {
            throw new LoadException(e.getMessage()); // already names the file and line
        }
    }
}
