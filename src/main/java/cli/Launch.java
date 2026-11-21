package cli;

import disasm.SymbolFile;
import disasm.SymbolFile.Symbol;
import disasm.SymbolFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import machine.LoadException;
import machine.Machine;

/**
 * How the Simulator started a Machine: the ROM, the image at its origin, the Disk and the symbol files that go with
 * them. Every error is a LoadException whose message names the file it is about.
 */
final class Launch {
    private final Machine machine;
    private final Path rom, image, disk;
    private final int org;
    private final boolean diskReadOnly;
    private final List<Path> syms;
    private List<Path> symbolFiles = List.of();

    /** A null {@code rom}, {@code image} or {@code disk} is left out; with no image the ROM boots. */
    Launch(Machine machine, Path rom, Path image, int org, Path disk, boolean diskReadOnly, List<Path> syms) {
        this.machine = machine;
        this.rom = rom;
        this.image = image;
        this.org = org;
        this.disk = disk;
        this.diskReadOnly = diskReadOnly;
        this.syms = List.copyOf(syms);
    }

    /**
     * Attaches the Disk and the ROM, then loads the image in Program mode or ROM boots. Returns the symbols of the
     * ROM's and the image's .sym next to them (when there), then of {@code syms}, in that order.
     */
    List<Symbol> start() throws LoadException {
        if (disk != null) about(disk, () -> machine.disk.attach(disk, diskReadOnly));
        return boot(false);
    }

    /**
     * Restart: the same start on the cleared Machine, every image and symbol file read afresh; the Disk stays attached.
     * Every file is read before the Machine is cleared, so a read or symbol file error leaves it as it was.
     */
    List<Symbol> restart() throws LoadException {
        return boot(true);
    }

    /** The images as {@code info} shows them. */
    List<String> images() {
        var images = new ArrayList<String>();
        if (rom != null) images.add(rom + " (ROM)");
        if (image != null) images.add(String.format("%s at $%04X", image, org));
        return images;
    }

    /** The symbol files of the last start or Restart, in load order. */
    List<String> symbolFiles() {
        return symbolFiles.stream().map(Path::toString).toList();
    }

    /** {@code prog.bin} -> {@code prog.sym}, next to it. */
    static Path sibling(Path image) {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return image.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".sym");
    }

    private List<Symbol> boot(boolean clear) throws LoadException {
        byte[] romBytes = rom == null ? null : read(rom), imageBytes = image == null ? null : read(image);
        var files = new ArrayList<Path>();
        for (Path p : new Path[] {rom, image}) if (p != null && Files.exists(sibling(p))) files.add(sibling(p));
        files.addAll(syms);
        var symbols = new ArrayList<Symbol>();
        for (Path p : files) about(p, () -> symbols.addAll(SymbolFile.parse(p.toString(), Files.readString(p))));
        // shortcut: a ROM, image or reset vector the Machine refuses is found only after clearing, so such a Restart
        // leaves the Machine cleared; check them before clearing if keeping the session matters there too.
        if (clear) machine.clearForRestart();
        if (romBytes != null) about(rom, () -> machine.attachRom(romBytes));
        if (imageBytes != null) about(image, () -> machine.loadProgram(imageBytes, org));
        else about(rom, machine::bootRom);
        symbolFiles = files;
        return symbols;
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
