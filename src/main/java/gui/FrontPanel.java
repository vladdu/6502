package gui;

import cli.Interpreter;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import machine.Machine;

/** The Front panel window in layout A: every pane shows the latest snapshot, every action runs its command through {@link #submit}. */
public final class FrontPanel {
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private final MachineThread machineThread;
    private final JFrame frame = new JFrame("6502 Front panel");
    private final JTextArea transcript = new JTextArea();
    private final JTextField command = new JTextField();
    private final History history = new History();
    private final ConsolePane console;
    /** The Machine thread's Stop count at the last refresh tick. */
    private int stops;
    private final CpuPane cpu = new CpuPane(MONO);
    private final DevicesPane devices = new DevicesPane(MONO, this::submit);
    private final MemoryViews memoryViews = new MemoryViews(MONO, this::submit);
    private final MemoryMapPane memoryMap = new MemoryMapPane(MONO, memoryViews::follow);
    private final DisassemblyPane disassembly = new DisassemblyPane(MONO, this::submit, memoryViews::follow);
    private final WatchBreakPane watchBreak = new WatchBreakPane(MONO, this::submit, disassembly::goTo, memoryViews::follow);
    /** The id of the snapshot the panes show; 0 before the first. */
    private long shown;
    /** The refresh tick: panes update from the Machine thread's output here. */
    private final Timer refresh = new Timer(50, _ -> refresh());
    private final KeyEventDispatcher userBreak = e -> userBreak(e) || switchFocus(e);

    /** Runs a launched Machine in the Front panel, Paused or running, until the window closes or {@code quit}; returns the exit code. */
    public static int run(Machine machine, BlockingQueue<Integer> consoleIn, ByteArrayOutputStream consoleOut,
            BiFunction<PrintWriter, Runnable, Interpreter> interpreter, boolean paused, List<String> warnings) {
        var thread = new MachineThread(machine, interpreter);
        FlatLightLaf.setup();
        var panel = new CompletableFuture<FrontPanel>();
        SwingUtilities.invokeLater(() -> panel.complete(new FrontPanel(thread, new ConsolePane(consoleIn, consoleOut, MONO), paused)));
        thread.start(paused, warnings);
        int code = thread.exit.join();
        SwingUtilities.invokeLater(() -> panel.join().dispose()); // after the build, which is queued first
        try {
            thread.awaitEnd(); // the Machine stops between Steps, so a Disk transfer isn't cut off by the JVM exiting
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return code;
    }

    private FrontPanel(MachineThread machineThread, ConsolePane console, boolean paused) {
        this.machineThread = machineThread;
        this.console = console;
        JComponent left = split(JSplitPane.VERTICAL_SPLIT, titled("CPU", cpu), titled("Devices", new JScrollPane(devices)), 0.25);
        JComponent centre = split(JSplitPane.VERTICAL_SPLIT, titled("Disassembly", disassembly), titled("Memory views", memoryViews), 0.4);
        JComponent right = split(JSplitPane.VERTICAL_SPLIT, titled("Memory map", memoryMap), titled("Watch / Break", watchBreak), 0.5);
        JComponent top = split(JSplitPane.HORIZONTAL_SPLIT, left, split(JSplitPane.HORIZONTAL_SPLIT, centre, right, 0.75), 0.22);
        var io = new JTabbedPane();
        io.addTab("Console", console.component);
        io.addTab("Screen", new JPanel());
        JComponent bottom = split(JSplitPane.HORIZONTAL_SPLIT, io, titled("Monitor", monitorPane()), 0.5);
        var root = new JPanel(new BorderLayout());
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(split(JSplitPane.VERTICAL_SPLIT, top, bottom, 0.62), BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                machineThread.close();
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(userBreak);
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (paused) command.requestFocusInWindow();
        else console.focus(); // running at once is a go
        refresh.start();
    }

    /** Run controls, then Outside controls: every button but Stop and Paste runs its command on the command path; Stop breaks like Ctrl-]. */
    private JToolBar toolbar() {
        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("Go", () -> submit("go")));
        bar.add(button("Stop", machineThread::requestBreak));
        bar.add(button("Step", () -> submit("step")));
        bar.add(button("Next", () -> submit("next")));
        bar.add(button("Restart", () -> submit("restart")));
        bar.addSeparator();
        bar.add(button("NMI", () -> submit("nmi")));
        bar.add(button("Reset", () -> submit("reset")));
        bar.add(button("Paste to Console", console::paste));
        return bar;
    }

    private static JButton button(String text, Runnable action) {
        var b = new JButton(text);
        b.setFocusable(false); // keeps focus where it was, the Console pane or the command line
        b.addActionListener(_ -> action.run());
        return b;
    }

    /** The transcript above the command line: Enter submits, Up/Down walk the history, Ctrl-C clears the line. */
    private JComponent monitorPane() {
        transcript.setName("transcript");
        command.setName("command");
        transcript.setFont(MONO);
        transcript.setEditable(false);
        command.setFont(MONO);
        command.addActionListener(_ -> {
            String line = command.getText();
            command.setText("");
            submit(line);
        });
        command.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP -> command.setText(history.previous());
                    case KeyEvent.VK_DOWN -> command.setText(history.next());
                    case KeyEvent.VK_C -> {
                        if (!e.isControlDown()) return;
                        command.setText("");
                    }
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
        var line = new JPanel(new BorderLayout());
        var prompt = new JLabel(" > ");
        prompt.setFont(MONO);
        line.add(prompt, BorderLayout.WEST);
        line.add(command, BorderLayout.CENTER);
        var p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(transcript), BorderLayout.CENTER);
        p.add(line, BorderLayout.SOUTH);
        return p;
    }

    /** The one posting path: a command line, typed or from a Front panel action, goes into the history and to the Machine thread. */
    void submit(String line) {
        history.add(line);
        machineThread.post(line);
        String w = line.trim().split("\\s+", 2)[0];
        if ((w.equals("go") || w.equals("continue")) && !machineThread.running()) console.focus(); // running, it is refused
    }

    /** Every refresh tick: the panes get the latest snapshot and ask a running Machine for the next, the Console pane and the transcript get what was written, and a new Stop moves focus to the command line and re-attaches the Disassembly pane. */
    private void refresh() {
        showSnapshot();
        console.refresh();
        int n = machineThread.stops();
        if (n != stops) {
            command.requestFocusInWindow();
            disassembly.attach();
        }
        stops = n;
        String s = machineThread.output();
        if (s.isEmpty()) return;
        transcript.append(s);
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    /** Each pane shows the latest snapshot, if it is new. */
    private void showSnapshot() {
        machineThread.requestSnapshot();
        Snapshot s = machineThread.snapshot();
        if (s == null || s.id() == shown) return;
        shown = s.id();
        cpu.display(s);
        devices.display(s);
        memoryViews.display(s);
        memoryMap.display(s);
        disassembly.display(s);
        watchBreak.display(s);
    }

    /** Ctrl-] anywhere in the window breaks, and is never typed into anything. */
    private boolean userBreak(KeyEvent e) {
        boolean ctrlBracket = (e.getKeyCode() == KeyEvent.VK_CLOSE_BRACKET && e.isControlDown()) || e.getKeyChar() == 0x1D;
        // typed too, for layouts where only the character arrives; a US layout breaks twice, and a stopped Machine ignores the second
        if (ctrlBracket && e.getID() != KeyEvent.KEY_RELEASED) machineThread.requestBreak();
        return ctrlBracket;
    }

    /** F6 toggles focus between the Console pane and the command line, and is never typed into anything. */
    private boolean switchFocus(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.VK_F6) return false;
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            if (console.focused()) command.requestFocusInWindow();
            else console.focus();
        }
        return true;
    }

    private void dispose() {
        refresh.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(userBreak);
        frame.dispose();
    }

    private static JComponent titled(String title, JComponent content) {
        var p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JSplitPane split(int orientation, Component a, Component b, double weight) {
        var s = new JSplitPane(orientation, a, b);
        s.setResizeWeight(weight);
        s.setContinuousLayout(true);
        return s;
    }
}
