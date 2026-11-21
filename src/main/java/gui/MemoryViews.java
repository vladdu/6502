package gui;

import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** The Memory views as tabs, zero page and the stack first, then a {@code +} tab that opens a new one; the selected tab is the current Memory view. */
final class MemoryViews extends JTabbedPane {
    private final Font mono;
    private final Consumer<String> submit;
    /** The latest snapshot, for a view opened while stopped; null before the first. */
    private Snapshot last;
    /** Opening a view moves the selection off the {@code +} tab and back. */
    private boolean opening;

    /** {@code submit} runs a command line on the command path. */
    MemoryViews(Font mono, Consumer<String> submit) {
        this.mono = mono;
        this.submit = submit;
        addTab("+", new JPanel());
        open("$0000");
        open("s");
        setSelectedIndex(0);
        addChangeListener(e -> {
            if (!opening && getSelectedIndex() == getTabCount() - 1) open("pc");
        });
    }

    private void open(String expression) {
        opening = true;
        var view = new MemoryViewPane(mono, expression, submit);
        view.addPropertyChangeListener("expression", e -> setTitleAt(indexOfComponent(view), view.expression()));
        insertTab(expression, null, view, null, getTabCount() - 1);
        if (last != null) view.display(last);
        setSelectedComponent(view);
        opening = false;
    }

    /** Every view shows a new snapshot. */
    void display(Snapshot s) {
        last = s;
        for (var c : getComponents()) if (c instanceof MemoryViewPane view) view.display(s);
    }

    /** The current Memory view follows {@code expression}, attached. */
    void follow(String expression) {
        if (getSelectedComponent() instanceof MemoryViewPane view) view.follow(expression);
    }
}
