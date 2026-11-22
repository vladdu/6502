package gui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import machine.DeviceState;

/** The Devices pane: one section per Device with its structured state, the Disk's Outside controls, and the Pins with their sources. */
final class DevicesPane extends JPanel {
    private final JLabel console = line(), timer = line(), timerCounts = line(), disk = line(), diskRegisters = line(),
            diskCommand = line(), irq = line(), nmi = line();
    private final JButton eject = new JButton("Eject");
    private final JCheckBox writeProtect = new JCheckBox("write-protect");
    private final JFileChooser chooser = new JFileChooser();

    /** Every control posts its {@code disk} command through {@code submit}. */
    DevicesPane(Font mono, Consumer<String> submit) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        var insert = new JButton("Insert...");
        insert.addActionListener(_ -> {
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) submit.accept("disk " + chooser.getSelectedFile());
        });
        eject.addActionListener(_ -> submit.accept("disk eject"));
        writeProtect.addActionListener(_ -> submit.accept(writeProtect.isSelected() ? "disk ro" : "disk rw"));
        var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JComponent c : List.of(insert, eject, writeProtect)) {
            c.setFocusable(false); // keeps focus where it was, the Console pane or the command line
            controls.add(c);
        }
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, controls.getPreferredSize().height)); // else BoxLayout gives the row all spare height
        section(mono, "Console", console);
        section(mono, "Timer", timer, timerCounts);
        section(mono, "Disk", disk, diskRegisters, diskCommand, controls);
        section(mono, "Exit device", new JLabel("no state"));
        section(mono, "Pins", irq, nmi);
    }

    /** Shows {@code s}; the write-protect box follows the Disk, so a refused {@code disk rw} leaves it ticked. */
    void display(Snapshot s) {
        for (DeviceState d : s.devices()) {
            switch (d) {
                case DeviceState.Console c -> console.setText(String.format("CONTROL $%02X  waiting %d  EOF %s  IRQ %s",
                        c.control(), c.waiting(), c.eof() ? "yes" : "no", c.irq() ? "high" : "low"));
                case DeviceState.Timer t -> {
                    timer.setText(String.format("%s  %s  IRQ %s  flag %s", t.running() ? "running" : "stopped",
                            t.periodic() ? "periodic" : "one-shot", t.irqEnabled() ? "on" : "off", t.flag() ? "set" : "clear"));
                    timerCounts.setText(String.format("reload $%04X  count $%04X  cycles %d", t.reload(), t.count(), t.cycles()));
                }
                case DeviceState.Disk k -> {
                    boolean inserted = k.image() != null;
                    disk.setText(inserted ? String.format("%s  %d Sector%s  %s", k.image(), k.sectors(), k.sectors() == 1 ? "" : "s",
                            k.readOnly() ? "read-only" : "read-write") : "no Disk image");
                    diskRegisters.setText(String.format("%s  %s  ERROR %d  SECTOR $%04X  PAGE $%02X", k.busy() ? "BUSY" : "idle",
                            k.done() ? "DONE" : "not done", k.error(), k.sector(), k.page()));
                    diskCommand.setText(String.format("command $%02X  elapsed %d", k.command(), k.elapsed()));
                    eject.setEnabled(inserted);
                    writeProtect.setEnabled(inserted);
                    writeProtect.setSelected(k.readOnly());
                }
            }
        }
        irq.setText(pin("IRQ", s.pins().irq()));
        nmi.setText(pin("NMI", s.pins().nmi()));
    }

    private static String pin(String name, List<String> sources) {
        return sources.isEmpty() ? name + "  low" : name + "  high  " + String.join(", ", sources);
    }

    private static JLabel line() {
        return new JLabel(" ");
    }

    private void section(Font mono, String title, JComponent... lines) {
        var t = new JLabel(title);
        t.setFont(getFont().deriveFont(Font.BOLD));
        t.setAlignmentX(LEFT_ALIGNMENT);
        add(t);
        for (JComponent c : lines) {
            if (c instanceof JLabel) c.setFont(mono);
            c.setAlignmentX(LEFT_ALIGNMENT);
            add(c);
        }
    }
}
