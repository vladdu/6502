package testdata;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;

/**
 * External CPU test data: pinned upstream sources, downloaded on first use into
 * {@code ${XDG_CACHE_HOME:-~/.cache}/6502-tests/<suite>/<sha>/}. A test whose data is not cached
 * and cannot be downloaded is aborted (reported as skipped).
 */
public final class TestData {
    private static final String SINGLE_STEP_SHA = "2f6980a2d95757486c7bee24355c360e40e2a224";
    private static final String DORMANN_SHA = "7954e2dbb49c469ea286070bf46cdd71aeb29e4b";
    private static final String DORMANN_FUNCTIONAL_SHA256 = "fa12bfc761e6f9057e4cc01a665a7b800ff01ae91f598af1e39a1201d01953fd";

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private TestData() {}

    /** SingleStepTests (SingleStepTests/65x02, MIT) for one NMOS opcode. */
    public static Path singleStep(int opcode) {
        String name = String.format("%02x.json", opcode);
        return fetch(cache("singlestep", SINGLE_STEP_SHA), name,
                URI.create("https://raw.githubusercontent.com/SingleStepTests/65x02/" + SINGLE_STEP_SHA + "/6502/v1/" + name), null);
    }

    /** Klaus Dormann's 6502 functional test image (GPL-3.0, downloaded, never committed). */
    public static Path dormannFunctional() {
        String name = "6502_functional_test.bin";
        return fetch(cache("dormann", DORMANN_SHA), name,
                URI.create("https://raw.githubusercontent.com/Klaus2m5/6502_65C02_functional_tests/" + DORMANN_SHA + "/bin_files/" + name),
                DORMANN_FUNCTIONAL_SHA256);
    }

    private static Path cache(String suite, String sha) {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path root = xdg == null || xdg.isEmpty() ? Path.of(System.getProperty("user.home"), ".cache") : Path.of(xdg);
        return root.resolve("6502-tests").resolve(suite).resolve(sha);
    }

    /**
     * {@code dir/name}, downloading {@code uri} first if absent. The download goes to a temp file in
     * {@code dir} and is renamed into place only when complete and, if {@code sha256} is given, verified.
     */
    static Path fetch(Path dir, String name, URI uri, String sha256) {
        Path target = dir.resolve(name);
        if (Files.exists(target)) return target;
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, name, ".part");
            var response = HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) Assumptions.abort("cannot download " + uri + ": HTTP " + response.statusCode());
            if (sha256 != null) {
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp)));
                if (!actual.equals(sha256)) fail(uri + ": SHA-256 " + actual + ", expected " + sha256);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            return Assumptions.abort("cannot download " + uri + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Assumptions.abort("interrupted downloading " + uri);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }
}
