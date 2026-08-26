package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.ui.settings.SettingsContributionSource;
import dev.turboism.sdk.ui.settings.SettingsSnapshot;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsChangeDecision;

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
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned core windows; plugins never receive Swing objects. */
final class CoreWindows implements AutoCloseable {
    private final PluginLocalization i18n;
    private final RuntimeSettingsService settings;
    private final SettingsContributionSource settingsContributions;
    private final CorePluginManagement plugins;
    private final CoreLogWindow logWindow;
    private JDialog settingsDialog;
    private JDialog pluginsDialog;
    private JDialog aboutDialog;
    private volatile boolean closed;
    private ActiveSettingsAction activeSettingsAction;
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
        final SettingsContributionSource settingsContributions,
        final CorePluginManagement plugins,
        final RuntimeLogReader logs
    ) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.settingsContributions = Objects.requireNonNull(
            settingsContributions,
            "settingsContributions"
        );
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.logWindow = new CoreLogWindow(i18n, logs);
    }

    void showSettings() {
        CoreDialogs.onEdt(() -> {
            if (settingsDialog != null) settingsDialog.dispose();
            settingsDialog = createSettingsDialog();
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
        closed = true;
        CoreDialogs.onEdt(() -> {
            logWindow.close();
            final ActiveSettingsAction active = activeSettingsAction;
            activeSettingsAction = null;
            if (active != null) {
                active.finished().set(true);
                active.handle().cancel();
                active.timer().stop();
                active.dialog().dispose();
            }
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
        final JComboBox<String> locale = new JComboBox<>(RuntimeSettings.LOCALE_OPTIONS.toArray(String[]::new));
        locale.setSelectedItem(value.locale());
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

        final Map<String, BuiltinTab> builtins = new LinkedHashMap<>();
        final JPanel runtime = form();
        add(runtime, 0, new JLabel(text("settings.log-level")), logLevel);
        add(runtime, 1, new JLabel(text("settings.max-log-storage-mib")), maxLogStorage);
        add(runtime, 2, new JLabel(text("settings.locale") + " (" + text("settings.locale.restart-required") + ")"), locale);
        add(runtime, 3, safeMode, new JLabel());
        builtins.put("runtime", new BuiltinTab(text("settings.tab.runtime"), 100, runtime));

        builtins.put(
            "performance",
            new BuiltinTab(text("settings.tab.performance"), 200, form())
        );

        final JPanel startup = form();
        add(startup, 0, skipUpdate, new JLabel());
        add(startup, 1, skipSplash, new JLabel());
        add(startup, 2, skipInformation, new JLabel());
        add(startup, 3, separateExportSaveDirectory, new JLabel());
        builtins.put("startup", new BuiltinTab(text("settings.tab.startup"), 300, startup));

        final JPanel maintenance = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        final JButton clean = new JButton(text("settings.clean-empty-docks"));
        clean.addActionListener(ignored -> CoreDialogs.message(
            dialog, text("common.turboism"), settings.cleanEmptyDocks().message()
        ));
        maintenance.add(clean);
        builtins.put(
            "maintenance",
            new BuiltinTab(text("settings.tab.maintenance"), 400, maintenance)
        );

        final RenderedSettings rendered = renderSettings(dialog, builtins);
        final JTabbedPane tabs = rendered.tabs();

        final JButton ok = new JButton(text("common.ok"));
        final JButton cancel = new JButton(text("common.cancel"));
        final JButton apply = new JButton(text("common.apply"));
        final Runnable save = () -> {
            settings.save(new RuntimeSettings(
                safeMode.isSelected(), (String) logLevel.getSelectedItem(),
                ((Number) maxLogStorage.getValue()).intValue(),
                skipUpdate.isSelected(), skipSplash.isSelected(), skipInformation.isSelected(),
                separateExportSaveDirectory.isSelected(), (String) locale.getSelectedItem()
            ));
            rendered.save().run();
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

    private RenderedSettings renderSettings(
        final JDialog owner,
        final Map<String, BuiltinTab> builtins
    ) {
        final List<RenderedTab> renderedTabs = new ArrayList<>();
        for (Map.Entry<String, BuiltinTab> entry : builtins.entrySet()) {
            renderedTabs.add(new RenderedTab(
                entry.getKey(), entry.getValue().title(), entry.getValue().index(), entry.getValue().panel()
            ));
        }
        final List<Runnable> saves = new ArrayList<>();
        for (SettingsSnapshot.Tab tab : settingsContributions.snapshot()) {
            RenderedTab rendered = renderedTabs.stream()
                .filter(candidate -> candidate.id().equals(tab.id()))
                .findFirst()
                .orElseGet(() -> {
                    final RenderedTab created = new RenderedTab(
                        tab.id(), tab.title(), tab.index().orElse(Integer.MAX_VALUE), form()
                    );
                    renderedTabs.add(created);
                    return created;
                });
            int row = rendered.panel().getComponentCount() / 2;
            for (SettingsSnapshot.Entry entry : tab.contributions()) {
                saves.add(renderControl(owner, rendered.panel(), row++, entry.contribution().control()));
            }
        }
        renderedTabs.sort(java.util.Comparator
            .comparingInt(RenderedTab::index)
            .thenComparing(RenderedTab::id));
        final JTabbedPane tabs = new JTabbedPane();
        for (RenderedTab tab : renderedTabs) tabs.addTab(tab.title(), tab.panel());
        return new RenderedSettings(tabs, () -> saves.forEach(Runnable::run));
    }

    private Runnable renderControl(
        final JDialog owner,
        final JPanel panel,
        final int row,
        final SettingsControl control
    ) {
        if (control instanceof SettingsControl.Choice choice) {
            final JComboBox<SettingsControl.Option> combo = new JComboBox<>(
                choice.options().toArray(SettingsControl.Option[]::new)
            );
            final String initial = requireChoiceValue(choice, choice.binding().read());
            select(combo, initial);
            final String[] accepted = {initial};
            final boolean[] changing = {false};
            combo.addActionListener(ignored -> {
                if (changing[0]) return;
                final SettingsControl.Option selected =
                    (SettingsControl.Option) combo.getSelectedItem();
                if (selected == null) return;
                final SettingsChangeDecision decision = choice.validator().validate(
                    accepted[0], selected.value()
                );
                if (decision.accepted()) {
                    accepted[0] = selected.value();
                    return;
                }
                changing[0] = true;
                try {
                    select(combo, accepted[0]);
                } finally {
                    changing[0] = false;
                }
                showRejectedChange(owner, decision);
            });
            add(panel, row, new JLabel(choice.label()), combo);
            return () -> choice.binding().write(accepted[0]);
        }
        if (control instanceof SettingsControl.Toggle toggle) {
            final boolean initial = Boolean.TRUE.equals(toggle.binding().read());
            final JCheckBox checkbox = new JCheckBox(toggle.label(), initial);
            final boolean[] accepted = {initial};
            final boolean[] changing = {false};
            checkbox.addActionListener(ignored -> {
                if (changing[0]) return;
                final boolean proposed = checkbox.isSelected();
                final SettingsChangeDecision decision = toggle.validator().validate(
                    accepted[0], proposed
                );
                if (decision.accepted()) {
                    accepted[0] = proposed;
                    return;
                }
                changing[0] = true;
                try {
                    checkbox.setSelected(accepted[0]);
                } finally {
                    changing[0] = false;
                }
                showRejectedChange(owner, decision);
            });
            add(panel, row, checkbox, new JLabel());
            return () -> toggle.binding().write(accepted[0]);
        }
        if (control instanceof SettingsControl.Text text) {
            final String initial = Objects.toString(text.binding().read(), "");
            final JTextField field = new JTextField(initial, text.columns());
            add(panel, row, new JLabel(text.label()), field);
            return () -> {
                final String proposed = field.getText();
                final SettingsChangeDecision decision = text.validator().validate(initial, proposed);
                if (!decision.accepted()) {
                    field.setText(initial);
                    showRejectedChange(owner, decision);
                    return;
                }
                text.binding().write(proposed);
            };
        }
        throw new IllegalArgumentException("unsupported settings control: " + control.getClass().getName());
    }

    private static String requireChoiceValue(
        final SettingsControl.Choice choice,
        final String value
    ) {
        final boolean present = choice.options().stream().anyMatch(option -> option.value().equals(value));
        if (!present) {
            throw new IllegalStateException(
                "settings binding returned an unknown choice for " + choice.id()
            );
        }
        return value;
    }

    private static void select(
        final JComboBox<SettingsControl.Option> combo,
        final String value
    ) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).value().equals(value)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        throw new IllegalArgumentException("unknown settings choice: " + value);
    }

    private void showRejectedChange(
        final JDialog owner,
        final SettingsChangeDecision decision
    ) {
        if (decision.action().isEmpty() && decision.link().isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(
                owner,
                decision.message(),
                decision.title(),
                javax.swing.JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        final List<Object> options = new ArrayList<>();
        decision.action().ifPresent(action -> options.add(action.label()));
        decision.link().ifPresent(link -> options.add(link.label()));
        options.add(text("common.cancel"));
        final int choice = javax.swing.JOptionPane.showOptionDialog(
            owner,
            decision.message(),
            decision.title(),
            javax.swing.JOptionPane.DEFAULT_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
            null,
            options.toArray(),
            options.get(0)
        );
        if (choice < 0) return;
        if (decision.action().isPresent()) {
            if (choice == 0) {
                runSettingsAction(owner, decision.action().orElseThrow());
                return;
            }
            if (choice == 1 && decision.link().isPresent()) {
                openSettingsLink(decision.link().orElseThrow());
            }
            return;
        }
        if (choice == 0 && decision.link().isPresent()) {
            openSettingsLink(decision.link().orElseThrow());
        }
    }

    private void runSettingsAction(
        final JDialog owner,
        final dev.turboism.sdk.ui.settings.SettingsDecisionAction action
    ) {
        final ActiveSettingsAction existing = activeSettingsAction;
        if (closed || existing != null && !existing.finished().get()) return;
        final dev.turboism.sdk.ui.settings.SettingsActionHandle handle;
        try {
            handle = action.action().start();
        } catch (RuntimeException failure) {
            CoreDialogs.message(
                owner,
                text("common.turboism"),
                text("settings.action.start-failed")
            );
            return;
        }
        if (closed) {
            handle.cancel();
            return;
        }
        final JDialog progressDialog = CoreDialogs.create(action.label(), 500, 150);
        if (progressDialog == null) {
            handle.cancel();
            return;
        }
        progressDialog.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        progressDialog.setLayout(new BorderLayout(8, 8));
        final JLabel message = new JLabel();
        final javax.swing.JProgressBar progress = new javax.swing.JProgressBar();
        final JButton cancel = new JButton(text("common.cancel"));
        final AtomicBoolean finished = new AtomicBoolean(false);
        cancel.addActionListener(ignored -> {
            if (handle.cancel()) {
                cancel.setEnabled(false);
                message.setText(text("settings.action.cancelling"));
            }
        });
        final JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(12, 16, 4, 16));
        center.add(message, BorderLayout.NORTH);
        center.add(progress, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        progressDialog.add(center, BorderLayout.CENTER);
        progressDialog.add(buttons, BorderLayout.SOUTH);

        final Runnable refresh = () -> {
            if (finished.get()) return;
            final dev.turboism.sdk.ui.settings.SettingsActionProgress value = handle.progress();
            message.setText(value.message());
            if (value.total() <= 0L) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setMaximum(1000);
                progress.setValue((int) Math.min(
                    1000L,
                    (value.completed() * 1000L) / value.total()
                ));
            }
        };
        final javax.swing.Timer timer = new javax.swing.Timer(150, ignored -> refresh.run());
        activeSettingsAction = new ActiveSettingsAction(
            handle, progressDialog, timer, finished
        );
        refresh.run();
        handle.completion().whenComplete((result, failure) -> CoreDialogs.onEdt(() ->
            finishSettingsAction(owner, result, failure, finished, timer, progressDialog)
        ));
        if (!finished.get()) {
            timer.start();
            CoreDialogs.show(progressDialog);
        }
    }

    private void finishSettingsAction(
        final JDialog owner,
        final dev.turboism.sdk.ui.settings.SettingsActionResult result,
        final Throwable failure,
        final AtomicBoolean finished,
        final javax.swing.Timer timer,
        final JDialog progressDialog
    ) {
        if (!finished.compareAndSet(false, true)) return;
        timer.stop();
        progressDialog.dispose();
        if (activeSettingsAction != null
            && activeSettingsAction.dialog() == progressDialog) {
            activeSettingsAction = null;
        }
        if (closed) return;
        if (failure != null || result == null) {
            CoreDialogs.message(
                owner,
                text("common.turboism"),
                text("settings.action.failed")
            );
            return;
        }
        CoreDialogs.message(owner, result.title(), result.message());
        if (result.succeeded() && settingsDialog == owner) {
            settingsDialog.dispose();
            settingsDialog = createSettingsDialog();
            CoreDialogs.show(settingsDialog);
        }
    }

    private void openSettingsLink(final dev.turboism.sdk.ui.settings.SettingsLink link) {
        try {
            if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("desktop browsing is unavailable");
            }
            Desktop.getDesktop().browse(link.uri());
        } catch (IOException | RuntimeException failure) {
            CoreDialogs.message(settingsDialog, text("common.turboism"), link.openFailureMessage());
        }
    }

    private record ActiveSettingsAction(
        dev.turboism.sdk.ui.settings.SettingsActionHandle handle,
        JDialog dialog,
        javax.swing.Timer timer,
        AtomicBoolean finished
    ) {
    }

    record BuiltinTab(String title, int index, JPanel panel) {
    }

    private record RenderedTab(String id, String title, int index, JPanel panel) {
    }

    record RenderedSettings(JTabbedPane tabs, Runnable save) {
    }

    private JDialog createPluginsDialog() {
        final JDialog dialog = CoreDialogs.create(text("window.plugins.title"), 900, 560);
        dialog.setLayout(new BorderLayout(8, 8));
        pluginTableModel = new PluginTableModel(i18n);
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

    static final class PluginTableModel extends AbstractTableModel {
        private final PluginLocalization i18n;
        private List<CorePluginManagement.PluginInfo> rows = List.of();
        private final String[] columns;
        PluginTableModel(final PluginLocalization i18n) {
            this.i18n = i18n;
            columns = new String[]{
                i18n.text("plugins.column.name"), i18n.text("plugins.column.version"), i18n.text("plugins.column.state"),
                i18n.text("plugins.column.desired"), i18n.text("plugins.column.pending"), i18n.text("plugins.column.id"),
                i18n.text("plugins.column.category"), i18n.text("plugins.column.tags")
            };
        }
        void setPlugins(final List<CorePluginManagement.PluginInfo> values) { rows = List.copyOf(values); fireTableDataChanged(); }
        CorePluginManagement.PluginInfo plugin(final int row) { return rows.get(row); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(final int column) { return columns[column]; }
        @Override public Object getValueAt(final int row, final int column) {
            final CorePluginManagement.PluginInfo plugin = rows.get(row);
            return switch (column) {
                case 0 -> plugin.name() + (plugin.core() ? " (" + i18n.text("plugins.core") + ")" : "");
                case 1 -> plugin.version();
                case 2 -> plugin.effectiveState();
                case 3 -> plugin.desiredState();
                case 4 -> plugin.pendingOperation().orElse("");
                case 5 -> plugin.id();
                case 6 -> i18n.text("plugin.category." + plugin.category());
                default -> String.join(", ", plugin.tags());
            };
        }
    }
}
