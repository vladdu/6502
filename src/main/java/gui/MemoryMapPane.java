package gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.JScrollPane;
import machine.Machine.Area;

/** The Memory map pane: one row per area (range, kind, name, and for a ROM area whether an image is attached), tinted by kind; a click points the current Memory view at the area. */
final class MemoryMapPane extends JScrollPane {
    private final JList<Area> list = new JList<>();
    private List<Area> shown = List.of();

    /** {@code follow} points the current Memory view at an expression. */
    MemoryMapPane(Font mono, Consumer<String> follow) {
        list.setFont(mono);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean selected, boolean focus) {
                var a = (Area) value;
                String kind = switch (a.kind()) {
                    case RAM -> "RAM";
                    case ROM -> "ROM";
                    case DEVICE -> "Device";
                    case UNMAPPED -> "unmapped";
                };
                String image = a.kind() != Area.Kind.ROM ? "" : a.attached() ? " (image)" : " (no image)";
                super.getListCellRendererComponent(l, String.format("$%04X-$%04X  %-8s  %s%s",
                        a.base(), a.base() + a.size() - 1, kind, a.name(), image), i, selected, focus);
                if (!selected) setBackground(MemoryViewPane.tint(a.kind()));
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()))
                    follow.accept(String.format("$%04X", list.getModel().getElementAt(i).base()));
            }
        });
        setViewportView(list);
        setPreferredSize(new Dimension(100, 100)); // the layout's weights share out the window, not the rows' width
    }

    /** Shows a new snapshot's Memory map, if it changed. */
    void display(Snapshot s) {
        if (s.areas().equals(shown)) return;
        shown = s.areas();
        list.setListData(shown.toArray(new Area[0]));
    }
}
