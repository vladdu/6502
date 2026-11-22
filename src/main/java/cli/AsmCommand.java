package cli;

import asm.Assembler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "asm", description = "Assemble SOURCE into BASE.bin, BASE.lst and BASE.sym.")
final class AsmCommand implements Callable<Integer> {
    /** Assembly errors; nothing was written. */
    static final int ERRORS = 1;

    @Spec CommandSpec spec;

    @Parameters(index = "0", paramLabel = "SOURCE", description = "Assembly source file.")
    Path source;

    @Option(names = "-o", paramLabel = "BASE", description = "Output path without extension (default: SOURCE minus extension).")
    String base;

    @Override
    public Integer call() {
        var err = spec.commandLine().getErr();
        Assembler.Result result;
        try {
            result = Assembler.assemble(source, Files::readAllBytes);
        } catch (IOException e) {
            err.println("6502: cannot read " + source + ": " + e);
            return ERRORS;
        }
        Assembler.Output out;
        switch (result) {
            case Assembler.Errors(var messages) -> {
                messages.forEach(err::println);
                return ERRORS;
            }
            case Assembler.Output o -> out = o;
        }
        String b = base != null ? base : source.toString().replaceFirst("\\.[^./\\\\]*$", "");
        try {
            Files.write(Path.of(b + ".bin"), out.binary());
            Files.writeString(Path.of(b + ".lst"), out.listing(), StandardCharsets.UTF_8);
            Files.writeString(Path.of(b + ".sym"), out.symbols(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("6502: cannot write " + b + ": " + e);
            return ERRORS;
        }
        return 0;
    }
}
