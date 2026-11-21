package gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import machine.Machine.Area;

/** One Memory view: its follow expression, error and Following toggle above a 64K table of 16-byte rows (see {@link MemoryView}). */
final class MemoryViewPane extends JPanel {
    private static final Color CHANGED = new Color(0xFFE066), MARGIN = new Color(0xF4F4F4), ERROR = new Color(0xC62828);
    private static final Border PC = BorderFactory.createLineBorder(new Color(0x1F6FEB), 2),
            STACK = BorderFactory.createLineBorder(new Color(0x8250DF), 2),
            WATCHED = BorderFactory.createMatteBorder(0, 0, 2, 0, new Color(0xD63384)),
            NONE = BorderFactory.createEmptyBorder(1, 1, 1, 1);

    /** The tint of a page's kind, shared with the Memory map pane. */
    static Color tint(Area.Kind kind) {
        return switch (kind) {
            case RAM -> Color.WHITE;
            case ROM -> new Color(0xE3EEFB);
            case DEVICE -> new Color(0xFCEBD7);
            case UNMAPPED -> new Color(0xE4E4E4);
        };
    }

    private final MemoryView view;
    private final Consumer<String> submit;
    private final JTextField expression = new JTextField(14);
    private final JLabel error = new JLabel();
    private final JToggleButton following = new JToggleButton("Following", true);
    private final JTable table;
    private final JScrollBar bar;
    /** This pane is moving the scroll bar, so the move is not scrolling by hand; the visible height the last move saw. */
    private boolean placing;
    private int extent;

    /** {@code submit} runs a command line on the command path. */
    MemoryViewPane(Font mono, String follow, Consumer<String> submit) {
        super(new BorderLayout());
        this.view = new MemoryView(follow);
        this.submit = submit;
        expression.setText(follow);
        expression.setFont(mono);
        expression.addActionListener(e -> follow(expression.getText()));
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

        table = new JTable(new Rows());
        table.setFont(mono);
        table.setRowHeight(mono.getSize() + 6);
        table.setShowGrid(false);
        table.setCellSelectionEnabled(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(48);
        for (int c = 1; c <= 16; c++) table.getColumnModel().getColumn(c).setPreferredWidth(26);
        table.getColumnModel().getColumn(17).setPreferredWidth(150);
        table.setDefaultRenderer(Object.class, new Renderer());
        var field = new JTextField();
        field.setFont(mono);
        table.setDefaultEditor(Object.class, new DefaultCellEditor(field) {
            @Override
            public Component getTableCellEditorComponent(JTable t, Object value, boolean selected, int row, int column) {
                Component c = super.getTableCellEditorComponent(t, value, selected, row, column);
                field.setText(""); // typing goes over the byte
                return c;
            }
        });
        table.setComponentPopupMenu(watchMenu());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (SwingUtilities.isRightMouseButton(e) && r >= 0 && c >= 0 && !table.isCellSelected(r, c)) table.changeSelection(r, c, false, false);
            }
        });
        var scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(100, 100)); // the layout's weights share out the window, not 64K of rows
        bar = scroll.getVerticalScrollBar();
        bar.addAdjustmentListener(e -> {
            boolean resized = bar.getVisibleAmount() != extent; // a resize can move the bar too, at the end of memory
            extent = bar.getVisibleAmount();
            if (resized) refresh();
            else if (!placing && view.snapshot() != null) {
                view.scroll(bar.getValue() / table.getRowHeight() * 16);
                following.setSelected(false);
            }
        });
        add(scroll, BorderLayout.CENTER);
    }

    /** The context menu over a byte or a drag selection: watch its bytes. */
    private JPopupMenu watchMenu() {
        var menu = new JPopupMenu();
        menu.add(watchItem("Watch writes", false, true));
        menu.add(watchItem("Watch reads", true, false));
        menu.add(watchItem("Watch reads and writes", true, true));
        return menu;
    }

    private JMenuItem watchItem(String text, boolean reads, boolean writes) {
        var item = new JMenuItem(text);
        item.addActionListener(e -> {
            int[] rows = table.getSelectedRows(), columns = table.getSelectedColumns();
            if (rows.length == 0 || columns.length == 0) return;
            int from = Math.max(1, columns[0]), to = Math.min(16, columns[columns.length - 1]); // the byte columns only
            if (from > to) return;
            submit.accept(MemoryView.watch(reads, writes, rows[0] * 16 + from - 1, rows[rows.length - 1] * 16 + to - 1));
        });
        return item;
    }

    String expression() {
        return view.expression();
    }

    /** Follows a new expression, attached. */
    void follow(String e) {
        String old = view.expression();
        view.follow(e);
        expression.setText(view.expression());
        refresh();
        firePropertyChange("expression", old, view.expression());
    }

    /** Shows a new snapshot. */
    void display(Snapshot s) {
        view.display(s);
        refresh();
    }

    /** Puts the top row in place while following, and shows the toggle, the error and the bytes. */
    private void refresh() {
        if (view.following()) {
            placing = true;
            bar.setValue(view.top() / 16 * table.getRowHeight());
            placing = false;
        }
        following.setSelected(view.following());
        error.setText(view.error() == null ? "" : view.error());
        table.repaint();
    }

    /** Row r holds $r0-$rF: the address, 16 bytes, the ASCII column. */
    private final class Rows extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return 0x1000;
        }

        @Override
        public int getColumnCount() {
            return 18;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 || c == 17 ? "" : String.format("%X", c - 1);
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (view.snapshot() == null) return "";
            if (c == 0) return String.format("%04X", r * 16);
            if (c == 17) return view.ascii(r * 16);
            return String.format("%02X", view.cell(r * 16 + c - 1).value());
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c >= 1 && c <= 16 && view.snapshot() != null;
        }

        /** Typing over a byte runs its {@code m} write; the next snapshot shows the result. */
        @Override
        public void setValueAt(Object value, int r, int c) {
            String command = MemoryView.edit(r * 16 + c - 1, value.toString());
            if (command != null) submit.accept(command);
        }
    }

    /** A byte tinted by its page's kind or highlighted as changed, outlined as PC or the stack byte, underlined when watched. */
    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, value, selected, focus, r, c);
            boolean isByte = c >= 1 && c <= 16 && view.snapshot() != null;
            MemoryView.Cell cell = isByte ? view.cell(r * 16 + c - 1) : null;
            Color background = cell == null ? MARGIN : cell.changed() ? CHANGED : tint(cell.kind());
            setBackground(selected ? t.getSelectionBackground() : background); // set every time: the renderer keeps the last one
            setForeground(selected ? t.getSelectionForeground() : t.getForeground());
            setBorder(cell == null ? NONE : cell.pc() ? PC : cell.stack() ? STACK : cell.watched() ? WATCHED : NONE);
            setToolTipText(cell == null ? null : view.tooltip(r * 16 + c - 1));
            return this;
        }
    }
}
