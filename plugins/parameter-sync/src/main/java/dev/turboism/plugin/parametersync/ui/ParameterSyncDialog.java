package dev.turboism.plugin.parametersync.ui;

import dev.turboism.plugin.parametersync.ParameterSyncPlugin;
import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

/**
 * Unified single dialog for parameter synchronization.
 *
 * <p>Top controls whether synchronization is enabled and displays the synchronized parameter count.
 * The central table allows each parameter to be independently checked into synchronization without
 * separate source, mode, or count columns.</p>
 */
public class ParameterSyncDialog extends JDialog {

    private final CubismModel model;
    private final SyncRuleSet ruleSet;
    private final ParameterSyncPlugin plugin;
    private final PluginLocalization i18n;

    private final JCheckBox globalEnabledCheck;
    private final JLabel countLabel;
    private final JTextField searchField;

    private final List<ParamRow> allRows = new ArrayList<>();
    private final List<ParamRow> filteredRows = new ArrayList<>();
    private final ParamTableModel tableModel;
    private final JTable table;

    public ParameterSyncDialog(
            final CubismModel model,
            final String focusedParamId,
            final SyncRuleSet ruleSet,
            final ParameterSyncPlugin plugin,
            final PluginLocalization i18n) {
        super((Frame) null, i18n != null ? i18n.text("dialog.sync.title") : "Parameter Synchronization", false);
        TurboismWindowFactory.style(this);
        setAlwaysOnTop(true);
        this.model = model;
        this.ruleSet = ruleSet;
        this.plugin = plugin;
        this.i18n = i18n;

        final JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // ── Top Panel: Global Enable Toggle & Parameter Count ──
        final JPanel topPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: Global Switch & Synchronized Count Label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        globalEnabledCheck = new JCheckBox(
                i18n != null ? i18n.text("dialog.top.enable") : "Enable Parameter Synchronization",
                ruleSet != null && ruleSet.isGlobalEnabled());
        globalEnabledCheck.setFont(globalEnabledCheck.getFont().deriveFont(Font.PLAIN, 14f));
        globalEnabledCheck.addActionListener(e -> {
            if (ruleSet != null) {
                ruleSet.setGlobalEnabled(globalEnabledCheck.isSelected());
                persist();
            }
        });
        topPanel.add(globalEnabledCheck, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        countLabel = new JLabel();
        countLabel.setFont(countLabel.getFont().deriveFont(Font.PLAIN));
        final JPanel countPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        countPanel.add(countLabel);
        final JButton clearBtn = new JButton(i18n != null ? i18n.text("dialog.btn.clear") : "Clear Selection");
        clearBtn.addActionListener(e -> clearSelection());
        countPanel.add(clearBtn);
        topPanel.add(countPanel, gbc);

        // Row 1: Search Filter Field
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        final JLabel searchLabel = new JLabel(i18n != null ? i18n.text("dialog.search.label") : "Filter:");
        topPanel.add(searchLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        searchField = new JTextField();
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                applyFilter();
            }
        });
        topPanel.add(searchField, gbc);

        content.add(topPanel, BorderLayout.NORTH);

        // ── Center Table: Checkbox, Parameter, Range, Current Value ──
        initRows(model, focusedParamId);
        tableModel = new ParamTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(26);
        table.getColumnModel().getColumn(0).setMaxWidth(65);
        table.getColumnModel().getColumn(2).setMaxWidth(130);
        table.getColumnModel().getColumn(3).setMaxWidth(110);

        final JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(680, 280));
        content.add(tableScroll, BorderLayout.CENTER);

        // ── Bottom Panel: Action Buttons ──
        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        final JButton saveBtn = new JButton(i18n != null ? i18n.text("dialog.btn.save") : "Save & Close");
        saveBtn.addActionListener(e -> {
            persist();
            dispose();
        });
        final JButton closeBtn = new JButton(i18n != null ? i18n.text("dialog.btn.close") : "Close");
        closeBtn.addActionListener(e -> dispose());
        buttonPanel.add(saveBtn);
        buttonPanel.add(closeBtn);

        content.add(buttonPanel, BorderLayout.SOUTH);

        updateCountLabel();

        setContentPane(content);
        pack();
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(null);

        // The dialog is non-modal: keep the Current column in step with the editor values.
        final Timer refreshTimer = new Timer(250, e -> {
            if (isShowing() && tableModel != null && !filteredRows.isEmpty()) {
                tableModel.fireTableChanged(
                        new javax.swing.event.TableModelEvent(tableModel, 0, filteredRows.size() - 1, 3));
            }
        });
        refreshTimer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(final java.awt.event.WindowEvent e) {
                refreshTimer.stop();
            }
        });
    }

    private void initRows(final CubismModel model, final String focusedId) {
        allRows.clear();
        if (model != null) {
            try {
                for (final Parameter p : model.parameters().all()) {
                    final String id = p.id().value();
                    final String name = p.name().filter(s -> !s.isBlank()).orElse(null);
                    boolean sync = ruleSet != null && ruleSet.isSynchronized(id);
                    if (focusedId != null && focusedId.equals(id) && ruleSet != null && ruleSet.isEmpty()) {
                        sync = true;
                        ruleSet.setSynchronized(id, true);
                    }
                    allRows.add(new ParamRow(sync, id, name, p.getMinimumValue(), p.getMaximumValue(), p));
                }
            } catch (RuntimeException ignored) {
            }
        }
        applyFilter();
    }

    private void applyFilter() {
        final String query = searchField != null ? searchField.getText().trim().toLowerCase(Locale.ROOT) : "";
        filteredRows.clear();
        for (final ParamRow row : allRows) {
            if (query.isEmpty()
                    || row.id.toLowerCase(Locale.ROOT).contains(query)
                    || (row.name != null && row.name.toLowerCase(Locale.ROOT).contains(query))) {
                filteredRows.add(row);
            }
        }
        if (tableModel != null) {
            tableModel.fireTableDataChanged();
        }
    }

    private void updateCountLabel() {
        final int count = (int) allRows.stream().filter(r -> r.sync).count();
        if (countLabel != null) {
            countLabel.setText(String.format(
                    i18n != null ? i18n.text("dialog.top.count") : "Synchronized: %d parameter(s)", count));
        }
    }

    private void clearSelection() {
        for (final ParamRow r : allRows) {
            r.sync = false;
        }
        if (ruleSet != null) {
            ruleSet.clear();
            persist();
        }
        tableModel.fireTableDataChanged();
        updateCountLabel();
    }

    private void persist() {
        if (ruleSet != null) {
            ruleSet.clear();
            for (final ParamRow r : allRows) {
                if (r.sync) {
                    ruleSet.setSynchronized(r.id, true);
                }
            }
        }
        if (plugin != null && model != null) {
            plugin.saveRules(model.id().value());
        }
    }

    /** Returns the parameter selection table. */
    public JTable table() {
        return table;
    }

    /** Returns the model-wide synchronization checkbox. */
    public JCheckBox globalEnabledCheck() {
        return globalEnabledCheck;
    }

    /** Returns the selected parameter count label. */
    public JLabel countLabel() {
        return countLabel;
    }

    private static final class ParamRow {
        boolean sync;
        final String id;
        final String name;
        final float min;
        final float max;
        final Parameter parameter;

        ParamRow(
                final boolean sync,
                final String id,
                final String name,
                final float min,
                final float max,
                final Parameter parameter) {
            this.sync = sync;
            this.id = id;
            this.name = name;
            this.min = min;
            this.max = max;
            this.parameter = parameter;
        }

        float current() {
            try {
                return parameter.getValue();
            } catch (RuntimeException failure) {
                return Float.NaN;
            }
        }

        String displayName() {
            return name != null ? id + " (" + name + ")" : id;
        }
    }

    private final class ParamTableModel extends AbstractTableModel {
        private final String[] columns = new String[] {
            i18n != null ? i18n.text("dialog.table.col.sync") : "Sync",
            i18n != null ? i18n.text("dialog.table.col.parameter") : "Parameter",
            i18n != null ? i18n.text("dialog.table.col.range") : "Range",
            i18n != null ? i18n.text("dialog.table.col.current") : "Current"
        };

        @Override
        public int getRowCount() {
            return filteredRows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(final int col) {
            return columns[col];
        }

        @Override
        public Class<?> getColumnClass(final int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(final int rowIndex, final int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(final int rowIndex, final int columnIndex) {
            if (rowIndex < 0 || rowIndex >= filteredRows.size()) {
                return null;
            }
            final ParamRow row = filteredRows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.sync;
                case 1 -> row.displayName();
                case 2 -> String.format("[%.1f, %.1f]", row.min, row.max);
                case 3 -> String.format(Locale.ROOT, "%.2f", row.current());
                default -> "";
            };
        }

        @Override
        public void setValueAt(final Object aValue, final int rowIndex, final int columnIndex) {
            if (rowIndex < 0 || rowIndex >= filteredRows.size()) {
                return;
            }
            final ParamRow row = filteredRows.get(rowIndex);
            if (columnIndex == 0 && aValue instanceof Boolean b) {
                row.sync = b;
                if (ruleSet != null) {
                    ruleSet.setSynchronized(row.id, b);
                    persist();
                }
                fireTableCellUpdated(rowIndex, columnIndex);
                updateCountLabel();
            }
        }
    }
}
