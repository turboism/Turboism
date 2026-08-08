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
import javax.swing.JEditorPane;
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
import javax.imageio.ImageIO;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private JDialog aboutDialog;
    static final String ABOUT_LOGO_TEXT = "Turboism";
    private static final Object ABOUT_LOGO_LOCK = new Object();
    private static volatile Path aboutLogoPng;
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

    void showAbout() {
        CoreDialogs.onEdt(() -> {
            if (aboutDialog == null) aboutDialog = createAboutDialog();
            CoreDialogs.show(aboutDialog);
        });
    }

    @Override
    public void close() {
        CoreDialogs.onEdt(() -> {
            logWindow.close();
            if (settingsDialog != null) settingsDialog.dispose();
            if (pluginsDialog != null) pluginsDialog.dispose();
            if (aboutDialog != null) aboutDialog.dispose();
            settingsDialog = null;
            pluginsDialog = null;
            aboutDialog = null;
        });
        final Path logo = aboutLogoPng;
        if (logo != null) {
            aboutLogoPng = null;
            try {
                Files.deleteIfExists(logo);
            } catch (IOException ignored) {
                // best-effort temp file cleanup
            }
        }
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

    private JDialog createAboutDialog() {
        ensureSwingUis();
        final JDialog dialog = CoreDialogs.create(text("window.about.title"), 380, 278);
        dialog.setLayout(new BorderLayout(0, 8));

        final JEditorPane content = new JEditorPane("text/html", aboutHtml(i18n, frameworkVersion()));
        content.setEditable(false);
        content.setOpaque(true);
        content.setBackground(Color.WHITE);

        final JButton close = new JButton(text("common.close"));
        close.addActionListener(ignored -> dialog.setVisible(false));
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(close);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        return dialog;
    }

    /**
     * Host sessions occasionally boot with a broken Swing look-and-feel (every
     * component reports "no ComponentUI class" and paints nothing). Register the
     * JDK Basic UIs for the components this dialog uses as a fallback so the
     * About dialog still renders when the host L&F is unhealthy.
     */
    private static void ensureSwingUis() {
        final javax.swing.UIDefaults defaults = javax.swing.UIManager.getDefaults();
        defaults.putIfAbsent("EditorPaneUI", "javax.swing.plaf.basic.BasicEditorPaneUI");
        defaults.putIfAbsent("PanelUI", "javax.swing.plaf.basic.BasicPanelUI");
        defaults.putIfAbsent("ButtonUI", "javax.swing.plaf.basic.BasicButtonUI");
        defaults.putIfAbsent("LabelUI", "javax.swing.plaf.basic.BasicLabelUI");
    }

    /**
     * Rendering follows the Turboism website style: a 42px bold gradient logo,
     * the framework version, the product tagline, and the thanks line. Swing's
     * HTML engine cannot paint {@code linear-gradient} text, so the logo is a
     * runtime-rendered gradient PNG embedded through {@code <img>}.
     */
    static String aboutHtml(final PluginLocalization i18n, final String version) {
        final String logo = logoImageTag();
        return "<html><head><meta charset=\"UTF-8\"><style>"
            + "body{margin:0;width:360px;height:220px;"
            + "font-family:Inter,\"Segoe UI\",\"Microsoft YaHei\",sans-serif;"
            + "background:#ffffff;color:#1f2937;}"
            + ".subtitle{margin-top:8px;font-size:13px;color:#6b7280;}"
            + ".thanks{margin-top:18px;font-size:12px;color:#9ca3af;text-align:center;line-height:1.7;}"
            + "</style></head><body>"
            + "<table width=\"360\" height=\"220\" cellpadding=\"0\" cellspacing=\"0\">"
            + "<tr><td align=\"center\" valign=\"middle\">"
            + "<div>" + logo + " <span class=\"subtitle\">" + version + "</span></div>"
            + "<div class=\"subtitle\">Live2D Cubism Extension Framework</div>"
            + "<div class=\"thanks\">" + i18n.text("about.thanks") + "</div>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    private static String logoImageTag() {
        try {
            return "<img src=\"" + gradientLogoPng().toUri().toURL().toExternalForm()
                + "\" alt=\"Turboism\">";
        } catch (IOException unavailable) {
            return "<span style=\"font-size:42px;font-weight:bold;color:#155dfc;\">Turboism</span>";
        }
    }

    static Path gradientLogoPng() throws IOException {
        final Path cached = aboutLogoPng;
        if (cached != null) return cached;
        synchronized (ABOUT_LOGO_LOCK) {
            if (aboutLogoPng != null) return aboutLogoPng;
            final Path file = Files.createTempFile("turboism-about-logo-", ".png");
            try {
                ImageIO.write(renderGradientLogo(), "png", file.toFile());
            } catch (IOException failure) {
                Files.deleteIfExists(file);
                throw failure;
            }
            aboutLogoPng = file;
            return file;
        }
    }

    private static BufferedImage renderGradientLogo() {
        final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 42);
        final BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        final FontMetrics metrics = measure.createGraphics().getFontMetrics(font);
        final int width = metrics.stringWidth(ABOUT_LOGO_TEXT) + 6;
        final int height = metrics.getHeight() + 4;
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font);
        graphics.setPaint(new LinearGradientPaint(
            0f, 0f, width, 0f, new float[]{0f, 1f}, new Color[]{new Color(0x155DFC), new Color(0xFCBB00)}
        ));
        graphics.drawString(ABOUT_LOGO_TEXT, 3, metrics.getAscent() + 2);
        graphics.dispose();
        return image;
    }

    static String frameworkVersion() {
        try (java.io.InputStream stream = CoreWindows.class.getResourceAsStream(
            "/META-INF/turboism/framework-version.properties"
        )) {
            if (stream == null) return "unknown";
            final java.util.Properties properties = new java.util.Properties();
            properties.load(stream);
            return properties.getProperty("version", "unknown");
        } catch (java.io.IOException unavailable) {
            return "unknown";
        }
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
