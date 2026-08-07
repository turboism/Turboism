package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;

/** Runtime-owned core windows; plugins never receive Swing objects. */
final class CoreWindows implements AutoCloseable {
    private final PluginLocalization i18n;
    private final RuntimeSettingsService settings;
    private final CorePluginManagement plugins;
    private final CoreLogWindow logWindow;
    private JDialog settingsDialog;
    private JDialog pluginsDialog;
    private PluginTableModel pluginTableModel;
    private JTable pluginTable;
    private TableRowSorter<PluginTableModel> pluginSorter;
    private JLabel pluginStatus;

    CoreWindows(
        final PluginLocalization i18n,
        final RuntimeSettingsService settings,
        final CorePluginManagement plugins,
        final RuntimeLogReader logs
    ) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.logWindow = new CoreLogWindow(i18n, logs);
    }

    void showSettings() {
        CoreDialogs.onEdt(() -> {
            if (settingsDialog == null) settingsDialog = createSettingsDialog();
            CoreDialogs.show(settingsDialog);
        });
    }

    void showPlugins() {
        CoreDialogs.onEdt(() -> {
            if (pluginsDialog == null) pluginsDialog = createPluginsDialog();
            refreshPlugins();
            CoreDialogs.show(pluginsDialog);
        });
    }

    void showLogs() {
        logWindow.show();
    }

    @Override
    public void close() {
        CoreDialogs.onEdt(() -> {
            logWindow.close();
            if (settingsDialog != null) settingsDialog.dispose();
            if (pluginsDialog != null) pluginsDialog.dispose();
            settingsDialog = null;
            pluginsDialog = null;
        });
    }

    private JDialog createSettingsDialog() {
        final RuntimeSettings value = settings.read();
        final JDialog dialog = CoreDialogs.create(text("window.settings.title"), 620, 460);
        dialog.setLayout(new BorderLayout());

        final JCheckBox safeMode = new JCheckBox(text("settings.safe-mode"), value.safeMode());
        final JComboBox<String> logLevel = new JComboBox<>(new String[]{"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"});
        logLevel.setSelectedItem(value.logLevel());
        final JSpinner maxLogStorage = new JSpinner(new SpinnerNumberModel(
            value.maxLogStorageMiB(),
            RuntimeSettings.MIN_MAX_LOG_STORAGE_MIB,
            RuntimeSettings.MAX_MAX_LOG_STORAGE_MIB,
            10
        ));
        final JCheckBox skipUpdate = new JCheckBox(text("settings.skip-update"), value.skipStartupUpdateCheck());
        final JCheckBox skipSplash = new JCheckBox(text("settings.skip-splash"), value.skipStartupSplash());
        final JCheckBox skipInformation = new JCheckBox(text("settings.skip-information"), value.skipStartupInformation());
        final JCheckBox separateExportSaveDirectory =
            new JCheckBox(text("settings.separate-export-save-directory"), value.separateExportSaveDirectory());

        final JTabbedPane tabs = new JTabbedPane();
        final JPanel runtime = form();
        add(runtime, 0, new JLabel(text("settings.log-level")), logLevel);
        add(runtime, 1, new JLabel(text("settings.max-log-storage-mib")), maxLogStorage);
        add(runtime, 2, safeMode, new JLabel());
        tabs.addTab(text("settings.tab.runtime"), runtime);

        final JPanel startup = form();
        add(startup, 0, skipUpdate, new JLabel());
        add(startup, 1, skipSplash, new JLabel());
        add(startup, 2, skipInformation, new JLabel());
        add(startup, 3, separateExportSaveDirectory, new JLabel());
        tabs.addTab(text("settings.tab.startup"), startup);

        final JPanel maintenance = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        final JButton clean = new JButton(text("settings.clean-empty-docks"));
        clean.addActionListener(ignored -> CoreDialogs.message(
            dialog, text("common.turboism"), settings.cleanEmptyDocks().message()
        ));
        maintenance.add(clean);
        tabs.addTab(text("settings.tab.maintenance"), maintenance);

        final JButton ok = new JButton(text("common.ok"));
        final JButton cancel = new JButton(text("common.cancel"));
        final JButton apply = new JButton(text("common.apply"));
        final Runnable save = () -> {
            settings.save(new RuntimeSettings(
                safeMode.isSelected(), (String) logLevel.getSelectedItem(),
                ((Number) maxLogStorage.getValue()).intValue(),
                skipUpdate.isSelected(), skipSplash.isSelected(), skipInformation.isSelected(),
                separateExportSaveDirectory.isSelected()
            ));
            CoreDialogs.message(dialog, text("common.turboism"), text("settings.saved"));
        };
        ok.addActionListener(ignored -> { save.run(); dialog.setVisible(false); });
        cancel.addActionListener(ignored -> dialog.setVisible(false));
        apply.addActionListener(ignored -> save.run());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        buttons.add(apply);
        dialog.add(tabs, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        return dialog;
    }

    private JDialog createPluginsDialog() {
        final JDialog dialog = CoreDialogs.create(text("window.plugins.title"), 900, 560);
        dialog.setLayout(new BorderLayout(8, 8));
        pluginTableModel = new PluginTableModel();
        pluginTable = new JTable(pluginTableModel);
        pluginTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pluginSorter = new TableRowSorter<>(pluginTableModel);
        pluginTable.setRowSorter(pluginSorter);
        pluginStatus = new JLabel(text("plugins.restart-hint"));

        final JTextField filter = new JTextField(20);
        filter.setToolTipText(text("plugins.filter.tooltip"));
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(final javax.swing.event.DocumentEvent event) { filter(); }
            @Override public void removeUpdate(final javax.swing.event.DocumentEvent event) { filter(); }
            @Override public void changedUpdate(final javax.swing.event.DocumentEvent event) { filter(); }
            private void filter() {
                final String value = filter.getText().trim();
                pluginSorter.setRowFilter(value.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(value)));
            }
        });

        final JButton install = new JButton(text("plugins.install"));
        install.addActionListener(ignored -> plugins.requestInstall(result ->
            CoreDialogs.onEdt(() -> operationCompleted(result))
        ));
        final JButton enable = new JButton(text("plugins.enable"));
        enable.addActionListener(ignored -> setSelectedEnabled(true));
        final JButton disable = new JButton(text("plugins.disable"));
        disable.addActionListener(ignored -> setSelectedEnabled(false));
        final JButton uninstall = new JButton(text("plugins.uninstall"));
        uninstall.addActionListener(ignored -> uninstallSelected());
        final JButton refresh = new JButton(text("common.refresh"));
        refresh.addActionListener(ignored -> refreshPlugins());
        final JButton close = new JButton(text("common.close"));
        close.addActionListener(ignored -> dialog.setVisible(false));

        final JPanel actions = new JPanel(new BorderLayout(8, 0));
        final JPanel filtering = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filtering.add(new JLabel(text("plugins.filter")));
        filtering.add(filter);
        actions.add(filtering, BorderLayout.WEST);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(install);
        buttons.add(enable);
        buttons.add(disable);
        buttons.add(uninstall);
        buttons.add(refresh);
        buttons.add(close);
        actions.add(buttons, BorderLayout.EAST);

        final JPanel bottom = new JPanel(new BorderLayout(8, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        bottom.add(pluginStatus, BorderLayout.NORTH);
        bottom.add(actions, BorderLayout.SOUTH);
        dialog.add(new JScrollPane(pluginTable), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        return dialog;
    }

    private void setSelectedEnabled(final boolean enabled) {
        final CorePluginManagement.PluginInfo plugin = selectedPlugin();
        if (plugin == null || plugin.core()) return;
        operationCompleted(plugins.setEnabled(plugin.id(), enabled));
    }

    private void uninstallSelected() {
        final CorePluginManagement.PluginInfo plugin = selectedPlugin();
        if (plugin == null || plugin.core()) return;
        if (CoreDialogs.confirm(
            pluginsDialog, text("plugins.uninstall"),
            i18n.format("plugins.uninstall.confirm", plugin.name())
        )) operationCompleted(plugins.uninstall(plugin.id()));
    }

    private CorePluginManagement.PluginInfo selectedPlugin() {
        final int row = pluginTable.getSelectedRow();
        return row < 0 ? null : pluginTableModel.plugin(pluginTable.convertRowIndexToModel(row));
    }

    private void operationCompleted(final CorePluginManagement.OperationResult result) {
        pluginStatus.setText(result.message());
        refreshPlugins();
    }

    private void refreshPlugins() {
        if (pluginTableModel != null) pluginTableModel.setPlugins(plugins.plugins());
    }

    private JPanel form() {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        return panel;
    }

    private static void add(final JPanel panel, final int row, final java.awt.Component left, final java.awt.Component right) {
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(6, 6, 6, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(left, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(right, constraints);
    }

    private String text(final String key) { return i18n.text(key); }

    private final class PluginTableModel extends AbstractTableModel {
        private List<CorePluginManagement.PluginInfo> rows = List.of();
        private final String[] columns = {
            text("plugins.column.name"), text("plugins.column.version"), text("plugins.column.state"),
            text("plugins.column.desired"), text("plugins.column.pending"), text("plugins.column.id")
        };
        void setPlugins(final List<CorePluginManagement.PluginInfo> values) { rows = List.copyOf(values); fireTableDataChanged(); }
        CorePluginManagement.PluginInfo plugin(final int row) { return rows.get(row); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(final int column) { return columns[column]; }
        @Override public Object getValueAt(final int row, final int column) {
            final CorePluginManagement.PluginInfo plugin = rows.get(row);
            return switch (column) {
                case 0 -> plugin.name() + (plugin.core() ? " (" + text("plugins.core") + ")" : "");
                case 1 -> plugin.version();
                case 2 -> plugin.effectiveState();
                case 3 -> plugin.desiredState();
                case 4 -> plugin.pendingOperation().orElse("");
                default -> plugin.id();
            };
        }
    }
}
