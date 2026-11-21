package gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;

/**
 * The Watch / Break pane: every breakpoint, then every Watchpoint (see {@link WatchBreakView}), the current hit in red. Delete or
 * Remove deletes a row's; double-clicking a breakpoint scrolls the Disassembly pane there, a Watchpoint has the current Memory view follow it.
 */
final class WatchBreakPane extends JScrollPane {
    private final JList<WatchBreakView.Row> list = new JList<>();
    private List<WatchBreakView.Row> shown = List.of();

    /** {@code submit} runs a command line on the command path; {@code goTo} scrolls the Disassembly pane; {@code follow} points the current Memory view at an expression. */
    WatchBreakPane(Font mono, Consumer<String> submit, IntConsumer goTo, Consumer<String> follow) {
        list.setName("watchbreak");
        list.setFont(mono);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean selected, boolean focus) {
                var r = (WatchBreakView.Row) value;
                super.getListCellRendererComponent(l, r.text(), i, selected, focus);
                if (r.hit()) setForeground(CpuPane.STOPPED);
                return this;
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_DELETE) return;
                for (WatchBreakView.Row r : list.getSelectedValuesList()) submit.accept(WatchBreakView.remove(r));
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                WatchBreakView.Row r = row(e);
                if (r == null || e.getClickCount() != 2 || e.isPopupTrigger()) return;
                if (r.breakpoint()) goTo.accept(r.address());
                else follow.accept(String.format("$%04X", r.address()));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                menu(e, submit);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                menu(e, submit);
            }
        });
        setViewportView(list);
        setPreferredSize(new Dimension(100, 100)); // the layout's weights share out the window, not the rows' width
    }

    private WatchBreakView.Row row(MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? shown.get(i) : null;
    }

    /** A row's context menu: Remove. */
    private void menu(MouseEvent e, Consumer<String> submit) {
        WatchBreakView.Row r = row(e);
        if (!e.isPopupTrigger() || r == null) return;
        var menu = new JPopupMenu();
        var remove = new JMenuItem("Remove");
        remove.addActionListener(a -> submit.accept(WatchBreakView.remove(r)));
        menu.add(remove);
        menu.show(list, e.getX(), e.getY());
    }

    /** Shows a new snapshot's rows, if they changed. */
    void display(Snapshot s) {
        List<WatchBreakView.Row> rows = WatchBreakView.rows(s);
        if (rows.equals(shown)) return;
        shown = rows;
        list.setListData(rows.toArray(new WatchBreakView.Row[0]));
    }
}
