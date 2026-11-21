package gui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cli.Main;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code run --gui} in-process on a real display: the whole window on a real Machine. */
@Tag("gui")
class FrontPanelTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void needsADisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless());
    }

    private CompletableFuture<Integer> run(String... args) {
        return CompletableFuture.supplyAsync(() -> Main.execute(args, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, UTF_8), new PrintStream(err, true, UTF_8)));
    }

    /** The Front panel's frame, once it is showing. */
    private static JFrame frame() throws InterruptedException {
        while (true) {
            for (Frame f : Frame.getFrames()) if (f instanceof JFrame j && j.isShowing()) return j;
            Thread.sleep(10);
        }
    }

    /** The first {@code type} component under {@code c} named {@code name} (null: any name). */
    private static <T> T find(Container c, Class<T> type, String name) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k) && (name == null || name.equals(k.getName()))) return type.cast(k);
            T found = k instanceof Container inner ? find(inner, type, name) : null;
            if (found != null) return found;
        }
        return null;
    }

    private static String text(JTextArea a, String wanted) throws Exception {
        while (true) {
            var t = new String[1];
            SwingUtilities.invokeAndWait(() -> t[0] = a.getText());
            if (t[0].contains(wanted)) return t[0];
            Thread.sleep(10);
        }
    }

    @Test
    void theWindowRunsTheProgramTakesCommandsAndClosesWithTheExitCode() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {(byte) 0xA9, 7, (byte) 0x8D, 0x00, (byte) 0xD3}); // LDA #7; STA $D300
        var code = run("run", "--gui", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTextField command = find(f.getContentPane(), JTextField.class, null);

        text(transcript, "Exited");
        SwingUtilities.invokeAndWait(() -> {
            command.setText("r");
            command.postActionEvent();
        });
        text(transcript, "> r\nPC=0205");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(7, code.get());
        while (f.isDisplayable()) Thread.sleep(10); // disposed after the session ends
    }

    @Test
    void consoleOutputShowsInTheConsolePaneAndKeysTypedThereBecomeConsoleBytes() throws Exception {
        Path prog = dir.resolve("echo.bin");
        Files.write(prog, new byte[] {
            (byte) 0xA9, 'h', (byte) 0x8D, 0x00, (byte) 0xD0, //       LDA #'h'; STA $D000
            (byte) 0xAD, 0x01, (byte) 0xD0, 0x10, (byte) 0xFB, // wait: LDA $D001; BPL wait
            (byte) 0xAD, 0x00, (byte) 0xD0, (byte) 0x8D, 0x00, (byte) 0xD0, //  LDA $D000; STA $D000
            (byte) 0x8D, 0x00, (byte) 0xD3}); //                       STA $D300: exit with the key
        var code = run("run", "--gui", prog.toString());
        JFrame f = frame();
        JTextArea console = find(f.getContentPane(), JTextArea.class, "console");

        text(console, "h");
        SwingUtilities.invokeAndWait(() -> console.dispatchEvent(
                new KeyEvent(console, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'k')));
        text(console, "hk");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals('k', code.get());
    }

    @Test
    void aLoadErrorExitsWith125AndNoWindow() throws Exception {
        assertEquals(125, run("run", "--gui", dir.resolve("missing.bin").toString()).get());
        assertTrue(err.toString(UTF_8).contains("missing.bin"), err.toString(UTF_8));
        assertTrue(Arrays.stream(Frame.getFrames()).noneMatch(Frame::isShowing));
    }
}
