package gui;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** The Console pane: Console output shown through {@link ConsoleText}; keys typed into it, and pastes, go to the Console. */
final class ConsolePane {
    private final BlockingQueue<Integer> in;
    private final ByteArrayOutputStream out;
    private final ConsoleText text = new ConsoleText();
    private final ConsoleKeys keys = new ConsoleKeys();
    private final JTextArea area = new JTextArea();
    final JComponent component = new JScrollPane(area);

    /** {@code in} is the Console's receive queue; {@code out} gets the Console's output, drained by {@link #refresh}. */
    ConsolePane(BlockingQueue<Integer> in, ByteArrayOutputStream out, Font font) {
        this.in = in;
        this.out = out;
        area.setName("console");
        area.setFont(font);
        area.setEditable(false);
        area.setFocusTraversalKeysEnabled(false); // Tab is a Console byte
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                send(keys.typed(e.getKeyChar()));
                e.consume();
            }
        });
    }

    /** Every refresh tick: shows all the Console output written since the last one. */
    void refresh() {
        byte[] bytes;
        synchronized (out) { // the Machine thread writes under the same lock
            bytes = out.toByteArray();
            out.reset();
        }
        text.write(bytes);
        ConsoleText.Edit e = text.take();
        if (e == null) return;
        area.replaceRange("", 0, e.trim());
        area.replaceRange(e.tail(), e.from(), area.getDocument().getLength());
        area.setCaretPosition(area.getDocument().getLength());
        if (e.bells() > 0) Toolkit.getDefaultToolkit().beep();
    }

    /** Paste to Console: the clipboard text, as UTF-8, into the receive queue; a beep if the clipboard holds no text. */
    void paste() {
        try {
            send(((String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor)).getBytes(UTF_8));
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    void focus() {
        area.requestFocusInWindow();
    }

    boolean focused() {
        return area.isFocusOwner();
    }

    private void send(byte[] bytes) {
        for (byte b : bytes) in.add(b & 0xFF);
    }
}
