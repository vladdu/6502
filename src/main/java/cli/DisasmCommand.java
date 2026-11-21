package cli;

import disasm.Disassembler;
import disasm.SymbolFile;
import disasm.SymbolFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "disasm", description = "Disassemble IMG to source on stdout that reassembles to the same bytes.")
final class DisasmCommand implements Callable<Integer> {
    /** Bad input: unreadable file, malformed symbol file, image past $FFFF. */
    static final int ERRORS = 1;

    @Spec CommandSpec spec;

    @Parameters(index = "0", paramLabel = "IMG", description = "Flat binary.")
    Path image;

    @Option(names = "--org", paramLabel = "ADDR", converter = Numbers.class, defaultValue = "$0200",
            description = "Load address of the image (default $0200).")
    int org;

    @Option(names = "--syms", paramLabel = "FILE", description = "Symbol file naming labels.")
    Path syms;

    @Override
    public Integer call() {
        var err = spec.commandLine().getErr();
        try {
            byte[] bytes = Files.readAllBytes(image);
            if ((long) org + bytes.length > 0x10000) {
                err.printf("6502: %s: org $%04X + size $%X runs past $FFFF%n", image, org, bytes.length);
                return ERRORS;
            }
            var symbols = syms == null ? new SymbolFile.Contents(List.of(), List.of()) : SymbolFile.read(syms);
            spec.commandLine().getOut().print(Disassembler.source(bytes, org, symbols.symbols(), symbols.data()));
            spec.commandLine().getOut().flush();
            return 0;
        } catch (IOException e) {
            err.println("6502: cannot read " + image + ": " + e);
        } catch (SymbolFileException e) {
            err.println(e.getMessage());
        }
        return ERRORS;
    }
}
