package dev.turboism.shell;

import dev.turboism.internal.core.KeybindingService;
import dev.turboism.keybinding.KeyStrokeCodec;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

/**
 * The core shell's shortcut editor: one window hosting a table of every bindable row —
 * plugin actions (including the core shell's own) and native-command translations. Every
 * mutation goes through {@link KeybindingService}; this class holds no binding state.
 *
 * <p>Capturing a keystroke installs a temporary {@link java.awt.KeyEventDispatcher} while
 * the global interceptor is suspended via {@link KeybindingService#suspendInterception()},
 * so the recorded keys never trigger a binding themselves.</p>
 */
final class KeybindingsWindow implements AutoCloseable {

    private final PluginLocalization i18n;
    private final KeybindingService keybindings;
    private JDialog dialog;
    private Model model;
    private JTable table;
    private TableRowSorter<Model> sorter;
    private JButton bind;
    private JButton clear;
    private JButton disable;
    private JButton remove;

    KeybindingsWindow(final PluginLocalization i18n, final KeybindingService keybindings) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.keybindings = Objects.requireNonNull(keybindings, "keybindings");
    }

    void show() {
        CoreDialogs.onEdt(() -> {
            if (dialog == null) dialog = createDialog();
            refresh();
            CoreDialogs.show(dialog);
        });
    }

    @Override
    public void close() {
        CoreDialogs.onEdt(() -> {
            if (dialog != null) dialog.dispose();
            dialog = null;
            model = null;
            table = null;
            sorter = null;
            bind = null;
            clear = null;
            disable = null;
            remove = null;
        });
    }

    private JDialog createDialog() {
        final JDialog value = CoreDialogs.create(text("window.keybindings.title"), 860, 520);
        value.setLayout(new BorderLayout(8, 8));

        model = new Model();
        table = new JTable(model) {
            @Override
            public String getToolTipText(final MouseEvent event) {
                final int viewRow = rowAtPoint(event.getPoint());
                if (viewRow < 0) return text("keybindings.table.hint");
                final KeybindingService.Row row = model.row(convertRowIndexToModel(viewRow));
                if (row.scope() == KeybindingService.Scope.NATIVE) {
                    return i18n.format("keybindings.row.native-key.tooltip", row.nativeStroke());
                }
                if (row.conflict()) return text("keybindings.row.conflict.tooltip");
                return text("keybindings.table.hint");
            }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(330);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.getSelectionModel().addListSelectionListener(ignored -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) return;
                final int viewRow = table.rowAtPoint(event.getPoint());
                final int viewColumn = table.columnAtPoint(event.getPoint());
                if (viewRow < 0 || viewColumn != Model.SHORTCUT_COLUMN) return;
                table.setRowSelectionInterval(viewRow, viewRow);
                bindSelected();
            }
        });

        final JTextField filter = new JTextField(18);
        filter.setToolTipText(text("keybindings.filter.tooltip"));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(final javax.swing.event.DocumentEvent event) {
                apply();
            }

            @Override
            public void removeUpdate(final javax.swing.event.DocumentEvent event) {
                apply();
            }

            @Override
            public void changedUpdate(final javax.swing.event.DocumentEvent event) {
                apply();
            }

            private void apply() {
                final String query = filter.getText().trim();
                sorter.setRowFilter(
                        query.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(query)));
            }
        });

        bind = new JButton(text("keybindings.bind"));
        bind.setToolTipText(text("keybindings.bind.tooltip"));
        bind.addActionListener(ignored -> bindSelected());
        clear = new JButton(text("keybindings.clear"));
        clear.setToolTipText(text("keybindings.clear.tooltip"));
        clear.addActionListener(ignored -> mutate(row -> keybindings.reset(row.id())));
        disable = new JButton(text("keybindings.disable"));
        disable.setToolTipText(text("keybindings.disable.tooltip"));
        disable.addActionListener(ignored -> mutate(row -> keybindings.disable(row.id())));
        remove = new JButton(text("keybindings.remove"));
        remove.setToolTipText(text("keybindings.remove.tooltip"));
        remove.addActionListener(ignored -> mutate(row -> keybindings.removeRow(row.id())));
        final JButton addNative = new JButton(text("keybindings.add-native"));
        addNative.setToolTipText(text("keybindings.add-native.tooltip"));
        addNative.addActionListener(ignored -> addNativeCommand());
        final JButton refresh = new JButton(text("common.refresh"));
        refresh.addActionListener(ignored -> refresh());
        final JButton close = new JButton(text("common.close"));
        close.addActionListener(ignored -> value.setVisible(false));

        final JPanel filtering = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        filtering.add(new JLabel(text("keybindings.filter")));
        filtering.add(filter);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(bind);
        buttons.add(clear);
        buttons.add(disable);
        buttons.add(addNative);
        buttons.add(remove);
        buttons.add(refresh);
        buttons.add(close);

        value.add(filtering, BorderLayout.NORTH);
        value.add(new JScrollPane(table), BorderLayout.CENTER);
        value.add(buttons, BorderLayout.SOUTH);
        return value;
    }

    private void refresh() {
        if (model == null) return;
        model.setRows(keybindings.snapshot());
        updateButtons();
    }

    private void updateButtons() {
        if (bind == null) return;
        final KeybindingService.Row row = selected();
        final boolean selected = row != null;
        bind.setEnabled(selected);
        clear.setEnabled(selected);
        disable.setEnabled(selected && !row.disabled());
        remove.setEnabled(selected && row.removable());
    }

    private KeybindingService.Row selected() {
        if (table == null || model == null) return null;
        final int view = table.getSelectedRow();
        return view < 0 ? null : model.row(table.convertRowIndexToModel(view));
    }

    /** Runs a row mutation then re-reads the snapshot; failures surface as a message. */
    private void mutate(final java.util.function.Consumer<KeybindingService.Row> mutation) {
        final KeybindingService.Row row = selected();
        if (row == null) return;
        try {
            mutation.accept(row);
        } catch (RuntimeException failure) {
            CoreDialogs.message(
                    dialog,
                    text("common.turboism"),
                    Objects.toString(failure.getMessage(), text("keybindings.mutation-failed")));
        }
        refresh();
    }

    private void bindSelected() {
        final KeybindingService.Row row = selected();
        if (row == null) return;
        final String captured = capture(dialog, text("keybindings.capture.title"));
        if (captured == null) return;
        mutate(ignored -> keybindings.bind(row.id(), captured));
    }

    private void addNativeCommand() {
        final JDialog form = TurboismWindowFactory.dialog(dialog, text("keybindings.add-native.title"), true);
        form.setLayout(new BorderLayout(8, 8));
        final JTextField label = new JTextField(22);
        final JLabel key = new JLabel(text("keybindings.capture.empty"));
        final String[] captured = {null};
        final JButton record = new JButton(text("keybindings.capture.record"));
        final JButton ok = new JButton(text("common.ok"));
        final JButton cancel = new JButton(text("common.cancel"));
        ok.setEnabled(false);
        record.addActionListener(ignored -> {
            final String value = capture(form, text("keybindings.capture-native.title"));
            if (value != null) {
                captured[0] = value;
                key.setText(value);
                ok.setEnabled(!label.getText().isBlank());
            }
        });
        label.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(final javax.swing.event.DocumentEvent event) {
                update();
            }

            @Override
            public void removeUpdate(final javax.swing.event.DocumentEvent event) {
                update();
            }

            @Override
            public void changedUpdate(final javax.swing.event.DocumentEvent event) {
                update();
            }

            private void update() {
                ok.setEnabled(captured[0] != null && !label.getText().isBlank());
            }
        });
        final boolean[] confirmed = {false};
        ok.addActionListener(ignored -> {
            confirmed[0] = true;
            form.dispose();
        });
        cancel.addActionListener(ignored -> form.dispose());

        final JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 12));
        fields.add(new JLabel(text("keybindings.add-native.label")));
        fields.add(label);
        fields.add(record);
        fields.add(key);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(ok);
        buttons.add(cancel);
        form.add(fields, BorderLayout.CENTER);
        form.add(buttons, BorderLayout.SOUTH);
        form.pack();
        form.setLocationRelativeTo(dialog);
        form.setVisible(true);
        if (!confirmed[0] || captured[0] == null) return;
        try {
            keybindings.addNativeCommand(label.getText().trim(), captured[0]);
        } catch (RuntimeException failure) {
            CoreDialogs.message(
                    dialog,
                    text("common.turboism"),
                    Objects.toString(failure.getMessage(), text("keybindings.mutation-failed")));
        }
        refresh();
    }

    /**
     * Modal keystroke recorder: suspends the global interceptor, installs a capture-only
     * dispatcher, and returns the canonical text of the first non-modifier key pressed.
     * Escape or closing the dialog cancels and yields {@code null}.
     */
    private String capture(final Window owner, final String title) {
        final Registration suspension = keybindings.suspendInterception();
        final String[] captured = {null};
        final JDialog recorder = TurboismWindowFactory.dialog(owner, title, true);
        recorder.setLayout(new BorderLayout(8, 8));
        final JLabel display = new JLabel(text("keybindings.capture.prompt"));
        display.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        final JButton cancel = new JButton(text("common.cancel"));
        cancel.addActionListener(ignored -> recorder.dispose());
        final JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        south.add(cancel);
        recorder.add(display, BorderLayout.CENTER);
        recorder.add(south, BorderLayout.SOUTH);

        final java.awt.KeyEventDispatcher grab = event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED) {
                return event.getID() == KeyEvent.KEY_TYPED;
            }
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE && event.getModifiersEx() == 0) {
                recorder.dispose();
                return true;
            }
            if (isModifier(event.getKeyCode())) {
                display.setText(modifierPreview(event.getModifiersEx()));
                return true;
            }
            final KeyStroke stroke = KeyStrokeCodec.normalize(KeyStroke.getKeyStrokeForEvent(event));
            if (stroke == null || stroke.getKeyCode() == KeyEvent.VK_UNDEFINED) {
                return true;
            }
            captured[0] = KeyStrokeCodec.encode(stroke);
            recorder.dispose();
            return true;
        };
        final KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        focus.addKeyEventDispatcher(grab);
        recorder.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(final WindowEvent event) {
                focus.removeKeyEventDispatcher(grab);
                suspension.close();
            }
        });
        recorder.pack();
        recorder.setSize(Math.max(360, recorder.getWidth()), recorder.getHeight());
        recorder.setLocationRelativeTo(owner);
        recorder.setVisible(true);
        return captured[0];
    }

    private static boolean isModifier(final int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT
                || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_META
                || keyCode == KeyEvent.VK_ALT_GRAPH;
    }

    private String modifierPreview(final int modifiersEx) {
        final StringBuilder text = new StringBuilder();
        if ((modifiersEx & InputEvent.CTRL_DOWN_MASK) != 0) text.append("Ctrl+");
        if ((modifiersEx & InputEvent.ALT_DOWN_MASK) != 0) text.append("Alt+");
        if ((modifiersEx & InputEvent.SHIFT_DOWN_MASK) != 0) text.append("Shift+");
        if ((modifiersEx & InputEvent.META_DOWN_MASK) != 0) text.append("Meta+");
        return text.append('…').toString();
    }

    private String text(final String key) {
        return i18n.text(key);
    }

    /** The row table: scope, command, effective shortcut, conflict marker. */
    private final class Model extends AbstractTableModel {
        static final int SHORTCUT_COLUMN = 2;

        private final String[] columns = {
            text("keybindings.column.scope"),
            text("keybindings.column.command"),
            text("keybindings.column.shortcut"),
            text("keybindings.column.conflict")
        };
        private List<KeybindingService.Row> rows = List.of();

        void setRows(final List<KeybindingService.Row> value) {
            rows = List.copyOf(value);
            fireTableDataChanged();
        }

        KeybindingService.Row row(final int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(final int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(final int index, final int column) {
            final KeybindingService.Row row = rows.get(index);
            return switch (column) {
                case 0 ->
                    row.scope() == KeybindingService.Scope.PLUGIN ? row.owner() : text("keybindings.scope.native");
                case 1 -> commandLabel(row);
                case 2 -> row.disabled() ? text("keybindings.disabled") : row.effectiveStroke();
                default -> row.conflict() ? text("keybindings.conflict") : "";
            };
        }

        /**
         * Built-in native rows resolve their label through the {@code keybindings.native.*}
         * catalog so the table is fully localized; custom rows keep their user-entered label.
         */
        private String commandLabel(final KeybindingService.Row row) {
            if (row.scope() == KeybindingService.Scope.NATIVE && !row.removable()) {
                final String key = "keybindings.native." + row.id().substring("native:".length());
                if (i18n.contains(key)) return i18n.text(key);
            }
            return row.label();
        }
    }
}
