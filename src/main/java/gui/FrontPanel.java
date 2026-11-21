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
import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import javax.swing.BorderFactory;
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

/** The Front panel window in layout A. The Monitor pane works; the other panes are placeholders for later tickets. */
public final class FrontPanel {
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);

    private final MachineThread machine;
    private final JFrame frame = new JFrame("6502 Front panel");
    private final JTextArea transcript = new JTextArea();
    private final JTextField command = new JTextField();
    private final History history = new History();
    /** The refresh tick: panes update from the Machine thread's output here. */
    private final Timer refresh = new Timer(50, e -> refresh());
    private final KeyEventDispatcher userBreak = this::userBreak;

    /**
     * Opens the Front panel on a launched Machine and runs it, Paused or running at once, until the window closes or {@code quit};
     * returns the exit code. {@code interpreter} builds the Interpreter (see {@link MachineThread}); {@code warning}, if not null,
     * starts the transcript.
     */
    public static int run(Machine machine, BiFunction<PrintWriter, Runnable, Interpreter> interpreter, boolean paused, String warning) {
        var thread = new MachineThread(machine, interpreter);
        FlatLightLaf.setup();
        var panel = new CompletableFuture<FrontPanel>();
        SwingUtilities.invokeLater(() -> panel.complete(new FrontPanel(thread)));
        thread.start(paused, warning);
        int code = thread.exit.join();
        SwingUtilities.invokeLater(() -> panel.join().dispose()); // after the build, which is queued first
        return code;
    }

    private FrontPanel(MachineThread machine) {
        this.machine = machine;
        JComponent left = split(JSplitPane.VERTICAL_SPLIT, titled("CPU", new JPanel()), titled("Devices", new JPanel()), 0.25);
        JComponent centre = split(JSplitPane.VERTICAL_SPLIT, titled("Disassembly", new JPanel()), titled("Memory views", new JTabbedPane()), 0.4);
        JComponent right = split(JSplitPane.VERTICAL_SPLIT, titled("Memory map", new JPanel()), titled("Watch / Break", new JPanel()), 0.5);
        JComponent top = split(JSplitPane.HORIZONTAL_SPLIT, left, split(JSplitPane.HORIZONTAL_SPLIT, centre, right, 0.75), 0.22);
        var io = new JTabbedPane();
        io.addTab("Console", new JPanel());
        io.addTab("Screen", new JPanel());
        JComponent bottom = split(JSplitPane.HORIZONTAL_SPLIT, io, titled("Monitor", monitorPane()), 0.5);
        var root = new JPanel(new BorderLayout());
        root.add(new JToolBar(), BorderLayout.NORTH);
        root.add(split(JSplitPane.VERTICAL_SPLIT, top, bottom, 0.62), BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                machine.close();
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(userBreak);
        frame.setSize(1400, 900);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        command.requestFocusInWindow();
        refresh.start();
    }

    /** The transcript above the command line: Enter submits, Up/Down walk the history, Ctrl-C clears the line. */
    private JComponent monitorPane() {
        transcript.setFont(MONO);
        transcript.setEditable(false);
        command.setFont(MONO);
        command.addActionListener(e -> {
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

    /** The one posting path: a command line, typed or from a GUI action, goes into the history and to the Machine thread. */
    void submit(String line) {
        history.add(line);
        machine.post(line);
    }

    /** Every refresh tick: the transcript gets what the Machine thread printed. */
    private void refresh() {
        String s = machine.output();
        if (s.isEmpty()) return;
        transcript.append(s);
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    /** Ctrl-] anywhere in the window breaks, and is never typed into anything. */
    private boolean userBreak(KeyEvent e) {
        boolean ctrlBracket = (e.getKeyCode() == KeyEvent.VK_CLOSE_BRACKET && e.isControlDown()) || e.getKeyChar() == 0x1D;
        if (ctrlBracket && e.getID() == KeyEvent.KEY_PRESSED) machine.requestBreak();
        return ctrlBracket;
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
