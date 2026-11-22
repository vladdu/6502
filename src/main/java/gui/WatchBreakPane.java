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
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;

/** The Watch / Break pane: the breakpoints, then the Watchpoints, in two lists (see {@link WatchBreakView}), the current hit in red. */
final class WatchBreakPane extends JPanel {
    private final JList<WatchBreakView.Row> breakpoints = new JList<>(), watchpoints = new JList<>();
    private List<WatchBreakView.Row> shown = List.of();

    /** {@code submit} runs a command line on the command path; {@code goTo} scrolls the Disassembly pane; {@code follow} points the current Memory view at an expression. */
    WatchBreakPane(Font mono, Consumer<String> submit, IntConsumer goTo, Consumer<String> follow) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        breakpoints.setName("breakpoints");
        watchpoints.setName("watchpoints");
        for (JList<WatchBreakView.Row> list : List.of(breakpoints, watchpoints)) {
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
                    WatchBreakView.Row r = row(list, e);
                    if (r == null || e.getClickCount() != 2 || e.isPopupTrigger()) return;
                    if (r.breakpoint()) goTo.accept(r.address());
                    else follow.accept(String.format("$%04X", r.address()));
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    menu(list, e, submit);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    menu(list, e, submit);
                }
            });
        }
        section("Breakpoints", breakpoints);
        section("Watchpoints", watchpoints);
        setPreferredSize(new Dimension(100, 100)); // the layout's weights share out the window, not the rows' width
    }

    private void section(String title, JList<WatchBreakView.Row> list) {
        var t = new JLabel(title);
        t.setAlignmentX(LEFT_ALIGNMENT);
        add(t);
        var scroll = new JScrollPane(list);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        add(scroll);
    }

    private static WatchBreakView.Row row(JList<WatchBreakView.Row> list, MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? list.getModel().getElementAt(i) : null;
    }

    /** A row's context menu: Remove. */
    private static void menu(JList<WatchBreakView.Row> list, MouseEvent e, Consumer<String> submit) {
        WatchBreakView.Row r = row(list, e);
        if (!e.isPopupTrigger() || r == null) return;
        var menu = new JPopupMenu();
        var remove = new JMenuItem("Remove");
        remove.addActionListener(_ -> submit.accept(WatchBreakView.remove(r)));
        menu.add(remove);
        menu.show(list, e.getX(), e.getY());
    }

    /** Shows a new snapshot's rows, if they changed. */
    void display(Snapshot s) {
        List<WatchBreakView.Row> rows = WatchBreakView.rows(s);
        if (rows.equals(shown)) return;
        shown = rows;
        breakpoints.setListData(rows.stream().filter(WatchBreakView.Row::breakpoint).toArray(WatchBreakView.Row[]::new));
        watchpoints.setListData(rows.stream().filter(r -> !r.breakpoint()).toArray(WatchBreakView.Row[]::new));
    }
}
