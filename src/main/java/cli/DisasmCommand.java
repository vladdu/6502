package cli;

import disasm.Disassembler;
import disasm.SymbolFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import machine.LoadException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "disasm", description = "Disassemble IMG to source on stdout that reassembles to the same bytes.")
final class DisasmCommand implements Callable<Integer> {
    /** Bad input: unreadable file, malformed or mismatched symbol file, image past $FFFF. */
    static final int ERRORS = 1;

    @Spec CommandSpec spec;

    @Parameters(index = "0", paramLabel = "IMG", description = "Flat binary.")
    Path image;

    @Option(names = "--org", paramLabel = "ADDR", converter = Numbers.class,
            description = "Load address of the image (default: its Symbol file's Image origin, else $0200).")
    Integer org;

    @Option(names = "--syms", paramLabel = "FILE", description = "Symbol file naming labels, with IMG's Image origin if it matches IMG; IMG's .sym next to it loads by itself.")
    Path syms;

    @Override
    public Integer call() {
        var err = spec.commandLine().getErr();
        try {
            byte[] bytes = Files.readAllBytes(image);
            var placed = syms == null ? Placement.of(image, bytes, org, Placement.REMEDY_OR_ORG) : Placement.named(image, bytes, org, syms);
            int address = placed.address();
            if ((long) address + bytes.length > 0x10000) {
                err.printf("6502: %s: load address $%04X + size $%X runs past $FFFF%n", image, address, bytes.length);
                return ERRORS;
            }
            if (syms == null && placed.symbolFile() != null)
                err.println("6502: using " + placed.symbolFile()); // one without a record can be stale unnoticed, so say which file
            for (String warning : placed.warnings()) err.println("6502: " + warning);
            var symbols = placed.contents();
            spec.commandLine().getOut().print(Disassembler.source(bytes, address, placed.entryPoint(), symbols.symbols(), symbols.data()));
            spec.commandLine().getOut().flush();
            return 0;
        } catch (IOException e) {
            err.println("6502: cannot read " + image + ": " + e);
        } catch (LoadException e) {
            err.println("6502: " + e.getMessage());
        } catch (SymbolFileException e) {
            err.println(e.getMessage());
        }
        return ERRORS;
    }
}
