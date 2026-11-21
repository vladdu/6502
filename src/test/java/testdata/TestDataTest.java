package testdata;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

class TestDataTest {
    private static final byte[] BODY = "hello".getBytes(StandardCharsets.US_ASCII);
    private static final String BODY_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir Path cache;
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Serves {@code declared} as Content-Length but only sends {@code body}, then hangs up. */
    @SuppressWarnings("AddressSelection") // a literal loopback IP, matched by the URI below; nothing to resolve
    private URI serve(byte[] body, int declared) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(200, declared);
            try (var out = ex.getResponseBody()) { out.write(body); }
            ex.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/f");
    }

    private static final URI UNREACHABLE = URI.create("http://127.0.0.1:1/f");

    @Test
    void downloadsOnceThenServesFromTheCache() throws IOException {
        URI uri = serve(BODY, BODY.length);

        Path first = TestData.fetch(cache.resolve("suite/sha"), "f.json", uri, null);
        Path second = TestData.fetch(cache.resolve("suite/sha"), "f.json", uri, null);

        assertEquals(cache.resolve("suite/sha/f.json"), first);
        assertEquals(first, second);
        assertArrayEquals(BODY, Files.readAllBytes(first));
        assertEquals(1, requests.get());
    }

    @Test
    void aCachedFileNeedsNoNetwork() throws IOException {
        Files.write(cache.resolve("f.json"), BODY);

        assertArrayEquals(BODY, Files.readAllBytes(TestData.fetch(cache, "f.json", UNREACHABLE, null)));
    }

    @Test
    void noNetworkAndEmptyCacheAbortsTheTest() {
        assertThrows(TestAbortedException.class, () -> TestData.fetch(cache, "f.json", UNREACHABLE, null));
        assertFalse(Files.exists(cache.resolve("f.json")));
    }

    @Test
    void aHalfDownloadedFileIsNeverUsed() throws IOException {
        URI uri = serve(BODY, 1000);

        assertThrows(TestAbortedException.class, () -> TestData.fetch(cache, "f.json", uri, null));
        try (var files = Files.list(cache)) {
            assertEquals(0, files.count(), "no target and no leftover temp file");
        }
    }

    @Test
    void aChecksumMismatchFailsAndKeepsNothing() throws IOException {
        URI uri = serve(BODY, BODY.length);

        assertThrows(AssertionFailedError.class, () -> TestData.fetch(cache, "f.bin", uri, "00".repeat(32)));
        try (var files = Files.list(cache)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void aMatchingChecksumIsAccepted() throws IOException {
        URI uri = serve(BODY, BODY.length);

        assertArrayEquals(BODY, Files.readAllBytes(TestData.fetch(cache, "f.bin", uri, BODY_SHA256)));
    }
}
