package gui;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cli.Main;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JTable;
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

    private static JButton button(Container c, String text) {
        for (Component k : c.getComponents()) {
            if (k instanceof JButton b && b.getText().equals(text)) return b;
            JButton found = k instanceof Container inner ? button(inner, text) : null;
            if (found != null) return found;
        }
        return null;
    }

    /** The CPU pane's lines, once they contain {@code wanted}; its first line is the run state. */
    private static String cpu(CpuPane p, String wanted) throws Exception {
        while (true) {
            var t = new String[1];
            SwingUtilities.invokeAndWait(() -> t[0] = Arrays.stream(p.getComponents()).map(c -> ((JLabel) c).getText())
                    .collect(Collectors.joining("\n")));
            if (t[0].contains(wanted)) return t[0];
            Thread.sleep(10);
        }
    }

    /** The Devices pane's label lines, once they contain {@code wanted}. */
    private static String devices(DevicesPane p, String wanted) throws Exception {
        while (true) {
            var t = new String[1];
            SwingUtilities.invokeAndWait(() -> t[0] = labels(p, new StringBuilder()).toString());
            if (t[0].contains(wanted)) return t[0];
            Thread.sleep(10);
        }
    }

    private static StringBuilder labels(Container c, StringBuilder b) {
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel l) b.append(l.getText()).append('\n');
            else if (k instanceof Container inner) labels(inner, b);
        }
        return b;
    }

    private static void click(AbstractButton b) throws Exception {
        SwingUtilities.invokeAndWait(b::doClick);
    }

    /** Clicks Insert... and picks {@code file} in its file chooser. */
    private static void insert(JFrame f, Path file) throws Exception {
        JButton b = button(f.getContentPane(), "Insert...");
        SwingUtilities.invokeLater(b::doClick); // the chooser is modal
        JFileChooser chooser = null;
        while (chooser == null) {
            Thread.sleep(10);
            for (Window w : Window.getWindows())
                if (w instanceof JDialog d && d.isShowing()) chooser = find(d.getContentPane(), JFileChooser.class, null);
        }
        JFileChooser c = chooser;
        SwingUtilities.invokeAndWait(() -> {
            c.setSelectedFile(file.toFile());
            c.approveSelection();
        });
    }

    @Test
    void theWindowRunsTheProgramTakesCommandsAndClosesWithTheExitCode() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {(byte) 0xA9, 7, (byte) 0x8D, 0x00, (byte) 0xD3}); // LDA #7; STA $D300
        var code = run("run", "--gui", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTextField command = find(f.getContentPane(), JTextField.class, "command");

        text(transcript, "Exited with code 7");
        CpuPane cpu = find(f.getContentPane(), CpuPane.class, null);
        String shown = cpu(cpu, "PC  0205");
        assertTrue(shown.startsWith("Exited with code 7\n"), shown);
        assertTrue(shown.contains("A   07"), shown);
        assertTrue(shown.contains("Steps   2"), shown);
        assertEquals(CpuPane.STOPPED, cpu.getComponent(0).getForeground());
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
    void theToolbarRunsCommandsThroughTheCommandLineAndTheCpuPaneFollows() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {(byte) 0xA9, 7, 0x4C, 0x02, 0x02}); // LDA #7; loop: JMP loop
        Files.writeString(dir.resolve("prog.sym"), "0202 loop label global\n");
        var code = run("run", "--gui", "--monitor", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        CpuPane cpu = find(f.getContentPane(), CpuPane.class, null);
        cpu(cpu, "Paused");

        click(button(f.getContentPane(), "Step"));
        text(transcript, "> step\nStep\n");
        cpu(cpu, "PC  0202  loop");
        click(button(f.getContentPane(), "Go"));
        cpu(cpu, "Running");
        assertEquals(CpuPane.RUNNING, cpu.getComponent(0).getForeground());
        click(button(f.getContentPane(), "Stop"));
        text(transcript, "User break");
        cpu(cpu, "User break");
        click(button(f.getContentPane(), "Restart"));
        cpu(cpu, "Steps   0");
        click(button(f.getContentPane(), "Next"));
        String t = text(transcript, "> next\nStep\n");
        assertTrue(t.contains("> go\n") && t.contains("> restart\nRestart\n"), t);
        cpu(cpu, "Steps   1");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void aTypedCtrlBracketCharacterBreaksAsOnLayoutsWithNoBracketKey() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTextArea console = find(f.getContentPane(), JTextArea.class, "console");
        cpu(find(f.getContentPane(), CpuPane.class, null), "Running");

        SwingUtilities.invokeAndWait(() -> console.dispatchEvent(
                new KeyEvent(console, KeyEvent.KEY_TYPED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_UNDEFINED, '\u001D')));
        text(transcript, "User break");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void aGoRefusedWhileRunningLeavesFocusOnTheCommandLine() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTextField command = find(f.getContentPane(), JTextField.class, "command");
        cpu(find(f.getContentPane(), CpuPane.class, null), "Running");
        SwingUtilities.invokeAndWait(command::requestFocusInWindow);
        await(command::isFocusOwner, true);

        SwingUtilities.invokeAndWait(() -> {
            command.setText("go");
            command.postActionEvent();
        });
        text(transcript, "> go\nrunning: stop first\n");
        Thread.sleep(100); // a focus request would have landed by now
        SwingUtilities.invokeAndWait(() -> assertTrue(command.isFocusOwner()));
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void theDevicesPaneShowsEveryDeviceAndThePinsAndNmiAndResetRunTheirCommands() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", "--monitor", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTextField command = find(f.getContentPane(), JTextField.class, "command");
        DevicesPane pane = find(f.getContentPane(), DevicesPane.class, null);

        String shown = devices(pane, "NMI  low");
        for (String s : List.of("Console\nCONTROL $00  waiting 0  EOF no  IRQ low\n", "Timer\nstopped  one-shot  IRQ off  flag clear\n",
                "Disk\nno Disk image\n", "Exit device\nno state\n", "Pins\nIRQ  low\nNMI  low"))
            assertTrue(shown.contains(s), s + " in\n" + shown);
        click(button(f.getContentPane(), "NMI"));
        text(transcript, "> nmi\n");
        devices(pane, "NMI  high  NMI button");
        click(button(f.getContentPane(), "Reset"));
        text(transcript, "> reset\nReset\n");
        SwingUtilities.invokeAndWait(() -> command.dispatchEvent(
                new KeyEvent(command, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED)));
        SwingUtilities.invokeAndWait(() -> assertEquals("reset", command.getText())); // in the history
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void theDiskControlsInsertEjectAndWriteProtectWhileRunning() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        Path disk = Files.write(dir.resolve("a.img"), new byte[512]);
        Path other = Files.write(dir.resolve("b.img"), new byte[256]);
        Path bad = Files.write(dir.resolve("bad.img"), new byte[100]);
        var code = run("run", "--gui", "--disk", disk.toString(), prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        DevicesPane pane = find(f.getContentPane(), DevicesPane.class, null);
        CpuPane cpu = find(f.getContentPane(), CpuPane.class, null);
        cpu(cpu, "Running");
        devices(pane, disk + "  2 Sectors  read-write");

        click(find(pane, JCheckBox.class, null));
        text(transcript, "> disk ro\n");
        devices(pane, disk + "  2 Sectors  read-only");
        click(button(pane, "Eject"));
        text(transcript, "> disk eject\n");
        devices(pane, "Disk\nno Disk image\n");
        insert(f, bad);
        text(transcript, "> disk " + bad + "\n" + bad + ": ");
        insert(f, other);
        text(transcript, "> disk " + other + "\n");
        devices(pane, other + "  1 Sector  read-write");
        cpu(cpu, "Running");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    /** {@code get} read on the EDT, once it equals {@code wanted}. */
    private static void await(java.util.function.Supplier<Object> get, Object wanted) throws Exception {
        while (true) {
            var v = new Object[1];
            SwingUtilities.invokeAndWait(() -> v[0] = get.get());
            if (wanted.equals(v[0])) return;
            Thread.sleep(10);
        }
    }

    @Test
    void typingOverAByteRunsItsWriteAndClickingAMemoryMapAreaPointsTheCurrentViewThere() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", "--monitor", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        MemoryViews views = find(f.getContentPane(), MemoryViews.class, null);
        JTable zeroPage = find(views, JTable.class, null);
        await(() -> zeroPage.getValueAt(0, 0), "0000");

        SwingUtilities.invokeAndWait(() -> zeroPage.getModel().setValueAt("4c", 0, 1)); // what the cell editor commits
        text(transcript, "> m $0000 = $4C\n");
        await(() -> zeroPage.getValueAt(0, 1), "4C");
        @SuppressWarnings("unchecked") // the Memory map pane's only list
        JList<Object> map = find(f.getContentPane(), JList.class, null);
        await(() -> map.getModel().getSize(), 8);
        SwingUtilities.invokeAndWait(() -> {
            Rectangle r = map.getCellBounds(7, 7); // $E000-$FFFF, the system ROM
            map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_CLICKED, 0, 0, r.x + 1, r.y + 1, 1, false, MouseEvent.BUTTON1));
        });
        await(() -> ((MemoryViewPane) views.getSelectedComponent()).expression(), "$E000");
        assertEquals("$E000", views.getTitleAt(0));
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void watchingADragSelectionWatchesTheBytesFromWhereTheDragStartedToWhereItEnded() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", "--monitor", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTable zeroPage = find(find(f.getContentPane(), MemoryViews.class, null), JTable.class, null);
        await(() -> zeroPage.getValueAt(0, 0), "0000");

        SwingUtilities.invokeAndWait(() -> {
            zeroPage.changeSelection(1, 3, false, false); // $0012
            zeroPage.changeSelection(0, 6, false, true); // dragged up and left to $0005
            ((JMenuItem) zeroPage.getComponentPopupMenu().getComponent(0)).doClick(); // Watch writes
        });
        text(transcript, "> w $0005 $E\n");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void theDisassemblyPaneListsTheCodeAtPcAndItsGutterSetsABreakpoint() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", "--monitor", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTable lines = find(f.getContentPane(), JTable.class, "disassembly");
        await(() -> lines.getRowCount() > 0 ? lines.getValueAt(0, 1) : "", "0200  4C 00 02  JMP $0200");

        SwingUtilities.invokeAndWait(() -> {
            Rectangle r = lines.getCellRect(0, 0, true);
            lines.dispatchEvent(new MouseEvent(lines, MouseEvent.MOUSE_CLICKED, 0, 0, r.x + 1, r.y + 1, 1, false, MouseEvent.BUTTON1));
        });
        text(transcript, "> b $0200\n");
        await(() -> lines.getValueAt(0, 0), "\u25CF");
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    /** The text of a Watch / Break list's rows, read on the EDT. */
    private static List<String> rows(JList<?> list) throws Exception {
        var t = new ArrayList<String>();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < list.getModel().getSize(); i++) t.add(((WatchBreakView.Row) list.getModel().getElementAt(i)).text());
        });
        return t;
    }

    @Test
    void theWatchBreakPaneListsBreakpointsAndWatchpointsSeparatelyAndDeleteRemovesARow() throws Exception {
        Path prog = dir.resolve("prog.bin");
        Files.write(prog, new byte[] {0x4C, 0x00, 0x02}); // loop: JMP loop
        var code = run("run", "--gui", "--monitor", prog.toString());
        JFrame f = frame();
        JTextArea transcript = find(f.getContentPane(), JTextArea.class, "transcript");
        JTextField command = find(f.getContentPane(), JTextField.class, "command");
        JList<?> breakpoints = find(f.getContentPane(), JList.class, "breakpoints");
        JList<?> watchpoints = find(f.getContentPane(), JList.class, "watchpoints");
        for (String line : List.of("b $0200", "w $0300")) {
            SwingUtilities.invokeAndWait(() -> {
                command.setText(line);
                command.postActionEvent();
            });
        }
        await(() -> watchpoints.getModel().getSize(), 1);
        assertEquals(List.of("$0200"), rows(breakpoints));
        assertEquals(List.of("$0300        w"), rows(watchpoints));

        SwingUtilities.invokeAndWait(() -> {
            watchpoints.setSelectedIndex(0);
            watchpoints.dispatchEvent(new KeyEvent(watchpoints, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED));
            breakpoints.setSelectedIndex(0);
            breakpoints.dispatchEvent(new KeyEvent(breakpoints, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED));
        });
        text(transcript, "> w del $0300\n");
        text(transcript, "> b del $0200\n");
        await(() -> breakpoints.getModel().getSize() + watchpoints.getModel().getSize(), 0);
        SwingUtilities.invokeAndWait(() -> f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING)));

        assertEquals(0, code.get());
    }

    @Test
    void aLoadErrorExitsWith125AndNoWindow() throws Exception {
        assertEquals(125, run("run", "--gui", dir.resolve("missing.bin").toString()).get());
        assertTrue(err.toString(UTF_8).contains("missing.bin"), err.toString(UTF_8));
        assertTrue(Arrays.stream(Frame.getFrames()).noneMatch(Frame::isShowing));
    }
}
