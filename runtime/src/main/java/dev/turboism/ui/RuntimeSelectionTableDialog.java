package dev.turboism.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.SelectionTableDialogRequest;
import dev.turboism.sdk.ui.SelectionTableDialogResultListener;
import dev.turboism.sdk.ui.SelectionTableFooter;
import dev.turboism.sdk.ui.resource.UiResourceService;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import dev.turboism.ui.resource.RuntimeUiResourceService;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.*;

/** Modal preview; no authoring work is performed here. */
final class RuntimeSelectionTableDialog extends JDialog {
    private List<String> result;

    static Registration openAsync(
            SelectionTableDialogRequest request,
            SelectionTableDialogResultListener listener,
            UiResourceService resources) {
        return openAsync(request, null, listener, resources);
    }

    static Registration openAsync(
            SelectionTableDialogRequest request,
            SelectionTableFooter footer,
            SelectionTableDialogResultListener listener,
            UiResourceService resources) {
        if (footer != null) SelectionTableFooter.requireValid(footer);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<RuntimeSelectionTableDialog> active = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            if (cancelled.get() || GraphicsEnvironment.isHeadless()) {
                listener.onResult(false, List.of());
                return;
            }
            RuntimeSelectionTableDialog dialog = new RuntimeSelectionTableDialog(request, footer, resources);
            active.set(dialog);
            try {
                dialog.setVisible(true);
                listener.onResult(dialog.result != null, dialog.result == null ? List.of() : dialog.result);
            } finally {
                active.set(null);
                dialog.dispose();
            }
        });
        return () -> {
            cancelled.set(true);
            SwingUtilities.invokeLater(() -> {
                var dialog = active.get();
                if (dialog != null) dialog.dispose();
            });
        };
    }

    RuntimeSelectionTableDialog(
            SelectionTableDialogRequest request, SelectionTableFooter footer, UiResourceService resources) {
        super((Frame) null, request.title(), true);
        TurboismWindowFactory.style(this);
        Rows data = new Rows(request);
        JTable table = new JTable(data);
        table.setRowHeight(28);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(125);
        table.getColumnModel().getColumn(0).setCellRenderer((t, value, selected, focused, row, column) -> {
            SelectionTableDialogRequest.Row item = data.rowAt(t.convertRowIndexToModel(row));
            JLabel label = new JLabel();
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setOpaque(true);
            label.setForeground(selected ? t.getSelectionForeground() : t.getForeground());
            label.setBackground(selected ? t.getSelectionBackground() : t.getBackground());
            label.setToolTipText(item.iconFallback());
            label.getAccessibleContext().setAccessibleName(item.iconFallback());
            var icon = RuntimeUiResourceService.resolveForPresentation(resources, item.icon(), false);
            if (icon.isPresent()) label.setIcon(icon.orElseThrow());
            else label.setText(item.iconFallback());
            return label;
        });
        JCheckBox all = new JCheckBox(request.columns().get(3));
        all.setHorizontalAlignment(SwingConstants.CENTER);
        Runnable sync = () -> {
            all.setSelected(data.allSelected());
            table.getTableHeader().repaint();
        };
        data.addTableModelListener(event -> sync.run());
        table.getColumnModel().getColumn(3).setHeaderRenderer((t, value, selected, focused, row, col) -> all);
        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (table.getTableHeader().columnAtPoint(event.getPoint()) == 3) {
                    if (table.isEditing()) table.getCellEditor().stopCellEditing();
                    data.selectAll(!data.allSelected());
                }
            }
        });
        JButton confirm = new JButton(request.acceptLabel());
        JButton cancel = new JButton(request.cancelLabel());
        confirm.addActionListener(event -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            result = data.selected();
            dispose();
        });
        cancel.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new BorderLayout(8, 0));
        if (footer != null) buttons.add(footerControl(footer, data), BorderLayout.WEST);
        JPanel decisions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        decisions.add(cancel);
        decisions.add(confirm);
        buttons.add(decisions, BorderLayout.EAST);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        if (!request.hint().isBlank()) root.add(new JLabel(request.hint()), BorderLayout.NORTH);
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(confirm);
        getRootPane()
                .registerKeyboardAction(
                        event -> dispose(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(640, 300));
        setSize(800, 500);
        setLocationRelativeTo(null);
        sync.run();
    }

    /**
     * Builds the footer control and rebuilds the preview when its choice changes.
     *
     * <p>The rebuild runs on the event dispatch thread the dialog already owns, and the accept path
     * reads the same model afterwards, so the confirmed rows are the ones the operator last saw.</p>
     */
    private static JPanel footerControl(SelectionTableFooter footer, Rows data) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel label = new JLabel(footer.label());
        JComboBox<SelectionTableFooter.Choice> choices =
                new JComboBox<>(footer.choices().toArray(SelectionTableFooter.Choice[]::new));
        for (SelectionTableFooter.Choice choice : footer.choices()) {
            if (choice.id().equals(footer.selectedChoiceId())) choices.setSelectedItem(choice);
        }
        label.setLabelFor(choices);
        choices.setName(footer.id());
        // Without a renderer the combo prints the record's toString, so the operator read
        // "Choice[id=part-name, label=...]" instead of the label.
        choices.setRenderer((list, value, index, selected, focused) -> {
            final JLabel cell = new JLabel(value == null ? "" : value.label());
            cell.setOpaque(true);
            cell.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            cell.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return cell;
        });
        choices.addActionListener(event -> {
            if (choices.getSelectedItem() instanceof SelectionTableFooter.Choice choice) {
                data.replaceRows(footer.rows(choice.id()));
            }
        });
        row.add(label);
        row.add(choices);
        return row;
    }

    static final class Rows extends AbstractTableModel {
        private List<SelectionTableDialogRequest.Row> rows;
        private boolean[] selected;
        private final String[] columns;

        Rows(SelectionTableDialogRequest request) {
            this.columns = request.columns().toArray(String[]::new);
            applyRows(request.rows());
        }

        private void applyRows(List<SelectionTableDialogRequest.Row> replacement) {
            this.rows = List.copyOf(replacement);
            this.selected = new boolean[rows.size()];
            for (int i = 0; i < selected.length; i++) selected[i] = rows.get(i).selected();
        }

        SelectionTableDialogRequest.Row rowAt(int row) {
            return rows.get(row);
        }

        /** Replaces every row, discarding the previous selection; used by a footer rebuild. */
        void replaceRows(List<SelectionTableDialogRequest.Row> replacement) {
            applyRows(java.util.Objects.requireNonNull(replacement, "replacement"));
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int col) {
            return columns[col];
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return col == 0 ? String.class : col == 3 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            var item = rows.get(row);
            return switch (col) {
                case 0 -> item.iconFallback();
                case 1 -> item.original();
                case 2 -> item.target();
                case 3 -> selected[row];
                default -> throw new IndexOutOfBoundsException();
            };
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 3 && rows.get(row).selectable();
        }

        @Override
        public void setValueAt(Object value, int row, int col) {
            if (isCellEditable(row, col)) {
                selected[row] = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, col);
            }
        }

        boolean allSelected() {
            boolean any = false;
            for (int i = 0; i < selected.length; i++)
                if (rows.get(i).selectable()) {
                    any = true;
                    if (!selected[i]) return false;
                }
            return any;
        }

        void selectAll(boolean value) {
            for (int i = 0; i < selected.length; i++)
                selected[i] = value && rows.get(i).selectable();
            fireTableDataChanged();
        }

        List<String> selected() {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < selected.length; i++)
                if (selected[i]) result.add(rows.get(i).id());
            return List.copyOf(result);
        }
    }
}
