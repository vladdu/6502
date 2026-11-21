package gui;

import cli.Interpreter;
import java.awt.Color;
import java.awt.Font;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** The CPU pane: the run state ("Running" in green, else the current Stop in red), registers with PC's label and P's flags, cycles and Steps. */
final class CpuPane extends JPanel {
    static final Color RUNNING = new Color(0x2E7D32), STOPPED = new Color(0xC62828);
    private final JLabel[] lines = new JLabel[6];

    CpuPane(Font mono) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        for (int i = 0; i < lines.length; i++) {
            lines[i] = new JLabel(" ");
            lines[i].setFont(i == 0 ? mono.deriveFont(Font.BOLD) : mono);
            add(lines[i]);
        }
    }

    /** Shows {@code s}. */
    void display(Snapshot s) {
        String label = s.symbols().label(s.pc());
        lines[0].setText(s.running() ? "Running" : s.stopText());
        lines[0].setForeground(s.running() ? RUNNING : STOPPED);
        lines[1].setText(String.format("PC  %04X%s", s.pc(), label == null ? "" : "  " + label));
        lines[2].setText(String.format("A   %02X   X   %02X   Y   %02X", s.a(), s.x(), s.y()));
        lines[3].setText(String.format("S   %02X   P   %s", s.s(), Interpreter.flags(s.p())));
        lines[4].setText("Cycles  " + s.cycles());
        lines[5].setText("Steps   " + s.steps());
    }
}
