package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** The Disassembly pane: its follow expression, error and Following toggle above the window of lines (see {@link DisassemblyView}) and a 64K scroll bar. */
final class DisassemblyPane extends JPanel {
    private static final Color PC_LINE = new Color(0xC9E4FF), PREVIOUS_PC = new Color(0xF1F5FA), DOT = new Color(0xB42318), ERROR = new Color(0xC62828);

    private final DisassemblyView view = new DisassemblyView("pc");
    private final Consumer<String> submit, showOperand;
    private final JTextField expression = new JTextField("pc", 14);
    private final JLabel error = new JLabel();
    private final JToggleButton following = new JToggleButton("Following", true);
    private final Lines model = new Lines();
    private final JTable table = new JTable(model);
    private final JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL, 0, 1, 0, 0x10000);
    /** This pane is moving the scroll bar, so the move is not scrolling by hand. */
    private boolean placing;
    /** The window's lines as last refreshed. */
    private List<DisassemblyView.Row> rows = List.of();

    /** {@code submit} runs a command line on the command path; {@code showOperand} has the current Memory view follow an expression. */
    DisassemblyPane(Font mono, Consumer<String> submit, Consumer<String> showOperand) {
        super(new BorderLayout());
        this.submit = submit;
        this.showOperand = showOperand;
        expression.setFont(mono);
        expression.addActionListener(e -> {
            view.follow(expression.getText());
            expression.setText(view.expression());
            refresh();
        });
        following.addActionListener(e -> {
            view.following(following.isSelected());
            refresh();
        });
        error.setForeground(ERROR);
        var header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        header.add(new JLabel("Follow"));
        header.add(expression);
        header.add(following);
        header.add(error);
        add(header, BorderLayout.NORTH);

        table.setName("disassembly");
        table.setFont(mono);
        table.setRowHeight(mono.getSize() + 6);
        table.setShowGrid(false);
        table.setTableHeader(null);
        table.setRowSelectionAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(20);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                DisassemblyView.Row r = row(e);
                if (r != null && SwingUtilities.isLeftMouseButton(e) && table.columnAtPoint(e.getPoint()) == 0) submit.accept(view.toggle(r.addr()));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                menu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                menu(e);
            }
        });
        table.addMouseWheelListener(e -> {
            for (int i = 0; i < Math.abs(e.getWheelRotation()) * 3; i++) {
                if (e.getWheelRotation() < 0) view.up();
                else view.down();
            }
            refresh();
        });
        table.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                view.rows(table.getHeight() / table.getRowHeight());
                refresh();
            }
        });
        var lines = new JPanel(new BorderLayout());
        lines.add(table, BorderLayout.CENTER);
        lines.setPreferredSize(new Dimension(100, 100)); // the layout's weights share out the window
        add(lines, BorderLayout.CENTER);
        bar.addAdjustmentListener(e -> scrolled());
        add(bar, BorderLayout.EAST);
    }

    /** The scroll bar moved by hand: its arrows step a line, its track a window of lines, its thumb goes to an address. */
    private void scrolled() {
        if (placing || view.snapshot() == null) return;
        int d = bar.getValue() - view.top(), lines = Math.max(1, rows.size() - 1);
        if (d == bar.getUnitIncrement()) view.down();
        else if (d == -bar.getUnitIncrement()) view.up();
        else if (d == bar.getBlockIncrement()) for (int i = 0; i < lines; i++) view.down();
        else if (d == -bar.getBlockIncrement()) for (int i = 0; i < lines; i++) view.up();
        else view.scroll(bar.getValue());
        refresh();
    }

    private DisassemblyView.Row row(MouseEvent e) {
        int r = table.rowAtPoint(e.getPoint());
        return r < 0 || r >= rows.size() ? null : rows.get(r);
    }

    /** A line's context menu: Run to here, Set PC here (stopped only), Show operand in Memory view, Go to target. */
    private void menu(MouseEvent e) {
        DisassemblyView.Row r = row(e);
        if (!e.isPopupTrigger() || r == null) return;
        var menu = new JPopupMenu();
        menu.add(item("Run to here", true, () -> submit.accept(DisassemblyView.runTo(r.addr()))));
        menu.add(item("Set PC here", !view.snapshot().running(), () -> submit.accept(DisassemblyView.setPc(r.addr()))));
        String operand = DisassemblyView.operand(r);
        menu.add(item("Show operand in Memory view", operand != null, () -> showOperand.accept(operand)));
        int target = view.target(r);
        menu.add(item("Go to target", target >= 0, () -> goTo(target)));
        menu.show(table, e.getX(), e.getY());
    }

    private static JMenuItem item(String text, boolean enabled, Runnable action) {
        var item = new JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Scrolls the pane to {@code address}, detached: Go to target, and a breakpoint row's double-click. */
    void goTo(int address) {
        view.goTo(address);
        refresh();
    }

    /** Every Stop: follows again. */
    void attach() {
        view.following(true);
        refresh();
    }

    /** Shows a new snapshot. */
    void display(Snapshot s) {
        view.display(s);
        refresh();
    }

    /** Puts the scroll bar at the top line and shows the toggle, the error and the lines. */
    private void refresh() {
        rows = view.snapshot() == null ? List.of() : view.lines();
        placing = true;
        bar.setValue(view.top());
        bar.setBlockIncrement(Math.max(1, rows.size() - 1));
        placing = false;
        following.setSelected(view.following());
        error.setText(view.error() == null ? "" : view.error());
        model.fireTableDataChanged();
    }

    /** The gutter, then the line as {@code d} prints it. */
    private final class Lines extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int r, int c) {
            DisassemblyView.Row row = rows.get(r);
            return c == 0 ? row.breakpoint() ? "●" : "" : row.text();
        }
    }

    /** The PC line highlighted, the previous PC's faintly; the breakpoint dot in red; a symbol operand's value as tooltip. */
    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, value, false, false, r, c);
            DisassemblyView.Row row = rows.get(r);
            setBackground(row.pc() ? PC_LINE : row.previous() ? PREVIOUS_PC : t.getBackground()); // set every time: the renderer keeps the last one
            setForeground(c == 0 ? DOT : t.getForeground());
            setToolTipText(c == 1 ? row.tooltip() : null);
            return this;
        }
    }
}
