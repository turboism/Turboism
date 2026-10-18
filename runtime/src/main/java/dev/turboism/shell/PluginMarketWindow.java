package dev.turboism.shell;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.internal.core.CorePluginManagement.OperationResult;
import dev.turboism.pluginmanagement.PluginCatalog;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPlugin;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginMarketService;
import dev.turboism.pluginmanagement.PluginMarketService.CatalogPluginLocalized;
import dev.turboism.pluginmanagement.PluginMarketService.PluginMarketStatus;
import dev.turboism.pluginmanagement.PluginMarketService.StatusType;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

/**
 * In-app Plugin Market window for browsing the signed Plugin Directory catalog,
 * checking compatibility, viewing READMEs, and performing verified installs and updates.
 */
final class PluginMarketWindow {

    private static final System.Logger LOGGER = System.getLogger(PluginMarketWindow.class.getName());
    private static final String MARKET_BASE_URL = "https://turboism.dev/plugins/";

    private final PluginLocalization i18n;
    private final PluginMarketService marketService;
    private final CorePluginManagement plugins;
    private final Locale currentLocale;
    private JDialog dialog;
    private MarketTableModel tableModel;
    private JTable table;
    private TableRowSorter<MarketTableModel> sorter;

    private JTextField searchField;
    private JComboBox<String> categoryCombo;
    private JComboBox<String> tagCombo;
    private JComboBox<String> authorCombo;
    private JComboBox<StatusTypeFilter> statusCombo;

    private JLabel statusLabel;
    private JPanel detailContainer;
    private JEditorPane readmePane;
    private CatalogPlugin selectedPlugin;

    PluginMarketWindow(
            final PluginLocalization i18n,
            final PluginMarketService marketService,
            final CorePluginManagement plugins,
            final Locale currentLocale) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.marketService = Objects.requireNonNull(marketService, "marketService");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.currentLocale = currentLocale != null ? currentLocale : Locale.getDefault();
    }

    void show() {
        CoreDialogs.onEdt(() -> {
            if (dialog != null) {
                dialog.toFront();
                return;
            }
            dialog = createDialog();
            CoreDialogs.show(dialog);
            refreshCatalog(false);
        });
    }

    void close() {
        CoreDialogs.onEdt(() -> {
            if (dialog != null) {
                dialog.dispose();
                dialog = null;
            }
        });
    }

    private JDialog createDialog() {
        final JDialog win = CoreDialogs.create(i18n.text("market.title"), 1050, 680);
        if (win == null) return null;
        win.setLayout(new BorderLayout(0, 0));

        tableModel = new MarketTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(28);
        table.getTableHeader().setReorderingAllowed(false);

        sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);

        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(4).setPreferredWidth(80);
        table.getColumnModel().getColumn(5).setPreferredWidth(130);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                final int viewRow = table.getSelectedRow();
                if (viewRow >= 0) {
                    final int modelRow = table.convertRowIndexToModel(viewRow);
                    selectedPlugin = tableModel.getPlugin(modelRow);
                    updateDetailPane(selectedPlugin);
                }
            }
        });

        // Top Filter Bar
        final JPanel topFilter = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        topFilter.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        searchField = new JTextField(15);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilters();
            }
        });

        categoryCombo = new JComboBox<>(new String[] {i18n.text("market.state.all")});
        categoryCombo.addActionListener(e -> applyFilters());

        tagCombo = new JComboBox<>(new String[] {i18n.text("market.state.all")});
        tagCombo.addActionListener(e -> applyFilters());

        authorCombo = new JComboBox<>(new String[] {i18n.text("market.state.all")});
        authorCombo.addActionListener(e -> applyFilters());

        statusCombo = new JComboBox<>(new StatusTypeFilter[] {
            new StatusTypeFilter(null, i18n.text("market.state.all")),
            new StatusTypeFilter(StatusType.INSTALLED, i18n.text("market.state.installed")),
            new StatusTypeFilter(StatusType.UPDATE_AVAILABLE, i18n.text("market.state.update-available")),
            new StatusTypeFilter(StatusType.NOT_INSTALLED, i18n.text("market.state.not-installed")),
            new StatusTypeFilter(StatusType.INCOMPATIBLE, i18n.text("market.state.incompatible")),
        });
        statusCombo.addActionListener(e -> applyFilters());

        final JButton refreshBtn = new JButton(i18n.text("common.refresh"));
        refreshBtn.addActionListener(e -> refreshCatalog(true));

        topFilter.add(new JLabel(i18n.text("market.search")));
        topFilter.add(searchField);
        topFilter.add(new JLabel(i18n.text("market.filter.category")));
        topFilter.add(categoryCombo);
        topFilter.add(new JLabel(i18n.text("market.filter.tag")));
        topFilter.add(tagCombo);
        topFilter.add(new JLabel(i18n.text("market.filter.author")));
        topFilter.add(authorCombo);
        topFilter.add(new JLabel(i18n.text("market.filter.state")));
        topFilter.add(statusCombo);
        topFilter.add(refreshBtn);

        // Center Split Pane
        final JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(580, 480));

        detailContainer = new JPanel(new BorderLayout());
        detailContainer.setPreferredSize(new Dimension(420, 480));
        detailContainer.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        readmePane = new JEditorPane("text/html", "");
        readmePane.setEditable(false);
        readmePane.setCaretPosition(0);

        final JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailContainer);
        splitPane.setDividerLocation(580);

        // Bottom Status Bar
        final JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        statusLabel = new JLabel(" ");
        final JButton closeBtn = new JButton(i18n.text("common.close"));
        closeBtn.addActionListener(e -> win.setVisible(false));
        bottomBar.add(statusLabel, BorderLayout.WEST);
        bottomBar.add(closeBtn, BorderLayout.EAST);

        win.add(topFilter, BorderLayout.NORTH);
        win.add(splitPane, BorderLayout.CENTER);
        win.add(bottomBar, BorderLayout.SOUTH);
        return win;
    }

    private void refreshCatalog(final boolean forceNetwork) {
        statusLabel.setText(i18n.text("market.status.loading"));
        marketService
                .loadCatalog(forceNetwork)
                .thenAccept(catalog -> CoreDialogs.onEdt(() -> {
                    tableModel.setCatalog(catalog);
                    populateFilterDropdowns(catalog);
                    applyFilters();
                    statusLabel.setText(" ");
                    if (table.getRowCount() > 0 && table.getSelectedRow() < 0) {
                        table.setRowSelectionInterval(0, 0);
                    }
                }))
                .exceptionally(err -> {
                    CoreDialogs.onEdt(() -> {
                        statusLabel.setText(i18n.text("market.status.error") + ": " + err.getMessage());
                    });
                    return null;
                });
    }

    private void populateFilterDropdowns(final PluginCatalog catalog) {
        final Set<String> categories = new HashSet<>();
        final Set<String> tags = new HashSet<>();
        final Set<String> authors = new HashSet<>();

        for (CatalogPlugin p : catalog.plugins()) {
            authors.add(p.author());
            for (CatalogRelease r : p.releases()) {
                categories.add(r.category());
                tags.addAll(r.tags());
            }
        }

        final String allText = i18n.text("market.state.all");
        updateCombo(categoryCombo, categories, allText);
        updateCombo(tagCombo, tags, allText);
        updateCombo(authorCombo, authors, allText);
    }

    private static void updateCombo(final JComboBox<String> combo, final Set<String> items, final String allText) {
        final String selected = (String) combo.getSelectedItem();
        final List<String> sorted = new ArrayList<>(items);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        sorted.add(0, allText);
        combo.setModel(new DefaultComboBoxModel<>(sorted.toArray(String[]::new)));
        if (selected != null && sorted.contains(selected)) {
            combo.setSelectedItem(selected);
        } else {
            combo.setSelectedIndex(0);
        }
    }

    private void applyFilters() {
        if (sorter == null) return;
        final String search = searchField.getText().trim().toLowerCase(currentLocale);
        final String selCategory = (String) categoryCombo.getSelectedItem();
        final String selTag = (String) tagCombo.getSelectedItem();
        final String selAuthor = (String) authorCombo.getSelectedItem();
        final StatusTypeFilter selStatus = (StatusTypeFilter) statusCombo.getSelectedItem();
        final String allText = i18n.text("market.state.all");

        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends MarketTableModel, ? extends Integer> entry) {
                final CatalogPlugin plugin = tableModel.getPlugin(entry.getIdentifier());
                final PluginMarketStatus status = tableModel.getStatus(entry.getIdentifier());

                if (selStatus != null && selStatus.type != null && status.type() != selStatus.type) {
                    return false;
                }
                if (selAuthor != null
                        && !allText.equals(selAuthor)
                        && !plugin.author().equals(selAuthor)) {
                    return false;
                }
                if (selCategory != null && !allText.equals(selCategory)) {
                    final boolean catMatches = plugin.releases().stream()
                            .anyMatch(r -> r.category().equals(selCategory));
                    if (!catMatches) return false;
                }
                if (selTag != null && !allText.equals(selTag)) {
                    final boolean tagMatches =
                            plugin.releases().stream().anyMatch(r -> r.tags().contains(selTag));
                    if (!tagMatches) return false;
                }
                if (!search.isEmpty()) {
                    final CatalogPluginLocalized loc = PluginMarketService.localizePlugin(plugin, currentLocale);
                    final String haystack = (plugin.id() + " " + plugin.slug() + " " + loc.name() + " "
                                    + loc.summary() + " " + plugin.author() + " "
                                    + String.join(" ", plugin.releases().get(0).tags()))
                            .toLowerCase(currentLocale);
                    if (!haystack.contains(search)) {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    private void updateDetailPane(final CatalogPlugin plugin) {
        detailContainer.removeAll();
        if (plugin == null) {
            detailContainer.revalidate();
            detailContainer.repaint();
            return;
        }

        final CatalogPluginLocalized loc = PluginMarketService.localizePlugin(plugin, currentLocale);
        final PluginMarketStatus status = marketService.resolveStatus(plugin);

        final JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        final JLabel nameLabel = new JLabel(loc.name());
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 18f));

        final JPanel metaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        final JLabel trustBadge =
                new JLabel(plugin.isOfficial() ? i18n.text("market.official") : i18n.text("market.third-party"));
        trustBadge.setOpaque(true);
        trustBadge.setBackground(plugin.isOfficial() ? new Color(220, 245, 230) : new Color(245, 235, 220));
        trustBadge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        final CatalogRelease targetRel =
                status.targetRelease().orElse(plugin.releases().get(0));
        final JLabel versionLabel = new JLabel("v" + targetRel.version());
        final JLabel categoryLabel = new JLabel("[" + targetRel.category() + "]");

        final JLabel hotReloadBadge = new JLabel(
                targetRel.hotReload()
                        ? i18n.text("market.hot-reload.supported")
                        : i18n.text("market.hot-reload.restart-required"));
        hotReloadBadge.setForeground(targetRel.hotReload() ? new Color(30, 130, 60) : new Color(150, 70, 30));

        metaRow.add(trustBadge);
        metaRow.add(versionLabel);
        metaRow.add(categoryLabel);
        metaRow.add(hotReloadBadge);

        final JLabel summaryLabel =
                new JLabel("<html><body style='width: 320px;'>" + escapeHtml(loc.summary()) + "</body></html>");
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 6, 2));

        final JPanel infoGrid = new JPanel(new GridBagLayout());
        int r = 0;
        gridRow(infoGrid, r++, i18n.text("plugins.details.author") + ":", plugin.author());
        gridRow(infoGrid, r++, i18n.text("plugins.details.license") + ":", plugin.license());
        gridRow(infoGrid, r++, i18n.text("plugins.details.tags") + ":", String.join(", ", targetRel.tags()));
        gridRow(infoGrid, r++, i18n.text("plugins.details.api") + ":", targetRel.turboismApi());
        gridRow(infoGrid, r++, i18n.text("plugins.details.state") + ":", formatStatusText(status));

        // Action Buttons Row
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        final JButton installOrUpdateBtn = new JButton();
        final JButton uninstallBtn = new JButton(i18n.text("market.action.uninstall"));
        final JButton toggleBtn = new JButton();
        final JButton browserBtn = new JButton(i18n.text("market.action.open-browser"));

        browserBtn.addActionListener(e -> openUrl(MARKET_BASE_URL + plugin.slug()));

        boolean canInstall = false;
        boolean canUninstall = false;
        boolean isInstalled = status.type() == StatusType.INSTALLED || status.type() == StatusType.UPDATE_AVAILABLE;

        if (status.type() == StatusType.NOT_INSTALLED) {
            installOrUpdateBtn.setText(i18n.text("market.action.install"));
            installOrUpdateBtn.setEnabled(true);
            canInstall = true;
        } else if (status.type() == StatusType.UPDATE_AVAILABLE) {
            installOrUpdateBtn.setText(i18n.text("market.action.update"));
            installOrUpdateBtn.setEnabled(true);
            canInstall = true;
        } else {
            installOrUpdateBtn.setText(i18n.text("market.action.install"));
            installOrUpdateBtn.setEnabled(false);
        }

        if (isInstalled) {
            canUninstall = !dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID.equals(plugin.id());
            uninstallBtn.setEnabled(canUninstall);

            final CorePluginManagement.PluginInfo info = plugins.plugins().stream()
                    .filter(p -> p.id().equals(plugin.id()))
                    .findFirst()
                    .orElse(null);
            final boolean enabled = info == null || "ENABLED".equals(info.desiredState());
            toggleBtn.setText(enabled ? i18n.text("market.action.disable") : i18n.text("market.action.enable"));
            toggleBtn.setEnabled(true);
            toggleBtn.addActionListener(e -> {
                final OperationResult res = plugins.setEnabled(plugin.id(), !enabled);
                handleOperationResult(res);
            });
            actions.add(toggleBtn);
        } else {
            uninstallBtn.setEnabled(false);
        }

        installOrUpdateBtn.addActionListener(e -> {
            if (!plugin.isOfficial()) {
                // Confirm requested permissions
                if (!confirmPermissions(plugin, targetRel)) {
                    return;
                }
            }
            statusLabel.setText(i18n.format("market.status.installing", loc.name()));
            installOrUpdateBtn.setEnabled(false);
            marketService
                    .installOrUpdate(plugin, targetRel)
                    .thenAccept(res -> CoreDialogs.onEdt(() -> {
                        handleOperationResult(res);
                        refreshCatalog(false);
                    }))
                    .exceptionally(err -> {
                        CoreDialogs.onEdt(() ->
                                statusLabel.setText(i18n.format("market.status.install-failed", err.getMessage())));
                        return null;
                    });
        });

        uninstallBtn.addActionListener(e -> {
            final OperationResult res = plugins.uninstall(plugin.id());
            handleOperationResult(res);
            refreshCatalog(false);
        });

        actions.add(installOrUpdateBtn);
        if (isInstalled) {
            actions.add(uninstallBtn);
        }
        actions.add(browserBtn);

        header.add(nameLabel);
        header.add(metaRow);
        header.add(summaryLabel);
        header.add(infoGrid);
        header.add(actions);

        // README / Details
        final Optional<String> bundledReadme =
                plugins.details(plugin.id()).flatMap(CorePluginManagement.PluginDetails::readme);
        final String readmeHtml = bundledReadme
                .map(PluginReadmeRenderer::render)
                .orElseGet(() -> PluginReadmeRenderer.render("# " + loc.name() + "\n\n" + loc.summary() + "\n\n" + "["
                        + i18n.text("market.action.open-browser") + "](" + MARKET_BASE_URL + plugin.slug() + ")"));
        readmePane.setText(readmeHtml);
        readmePane.setCaretPosition(0);

        final JScrollPane readmeScroll = new JScrollPane(readmePane);
        readmeScroll.setBorder(BorderFactory.createTitledBorder(i18n.text("plugins.details.readme")));

        detailContainer.add(header, BorderLayout.NORTH);
        detailContainer.add(readmeScroll, BorderLayout.CENTER);
        detailContainer.revalidate();
        detailContainer.repaint();
    }

    private boolean confirmPermissions(final CatalogPlugin plugin, final CatalogRelease release) {
        final List<CatalogPermission> permissions = release.permissions();
        final StringBuilder msg = new StringBuilder();
        msg.append(i18n.text("market.permission.confirm.message")).append("\n\n");
        if (permissions.isEmpty()) {
            msg.append("- ").append(i18n.text("common.none")).append("\n");
        } else {
            for (CatalogPermission p : permissions) {
                msg.append("• ").append(p.id()).append(" (").append(p.scope()).append(")");
                if (!p.reason().isBlank()) {
                    msg.append(": ").append(p.reason());
                }
                msg.append("\n");
            }
        }
        final int choice = JOptionPane.showConfirmDialog(
                dialog,
                msg.toString(),
                i18n.text("market.permission.confirm.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    private void handleOperationResult(final OperationResult result) {
        final String feedback = "PLUGIN_HOT_APPLIED".equals(result.code())
                ? i18n.text("market.applied")
                : i18n.text("market.restart-required");
        statusLabel.setText(feedback + " (" + result.message() + ")");
    }

    private String formatStatusText(final PluginMarketStatus status) {
        return switch (status.type()) {
            case INSTALLED ->
                i18n.text("market.state.installed") + " ("
                        + status.installedVersion().orElse("") + ")";
            case UPDATE_AVAILABLE ->
                i18n.text("market.state.update-available") + " ("
                        + status.installedVersion().orElse("") + " -> "
                        + status.targetRelease().map(CatalogRelease::version).orElse("") + ")";
            case NOT_INSTALLED -> i18n.text("market.state.not-installed");
            case INCOMPATIBLE ->
                i18n.text("market.state.incompatible") + " ("
                        + status.incompatibleReason().orElse("") + ")";
        };
    }

    private static void gridRow(final JPanel panel, final int row, final String label, final String value) {
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 2, 2, 8);
        final JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(new JLabel(value != null && !value.isBlank() ? value : "-"), gbc);
    }

    private static void openUrl(final String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            LOGGER.log(System.Logger.Level.DEBUG, "Failed to open URL: " + url, failure);
        }
    }

    private static String escapeHtml(final String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record StatusTypeFilter(StatusType type, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    final class MarketTableModel extends AbstractTableModel {
        private final String[] columns = new String[] {
            i18n.text("plugins.details.name"),
            i18n.text("plugins.details.version"),
            i18n.text("plugins.details.category"),
            i18n.text("plugins.details.author"),
            i18n.text("market.title"), // Official vs Third party
            i18n.text("plugins.details.state")
        };
        private List<CatalogPlugin> pluginRows = List.of();
        private List<PluginMarketStatus> statusRows = List.of();

        void setCatalog(final PluginCatalog catalog) {
            if (catalog == null) {
                pluginRows = List.of();
                statusRows = List.of();
            } else {
                pluginRows = List.copyOf(catalog.plugins());
                final List<PluginMarketStatus> statuses = new ArrayList<>();
                for (CatalogPlugin p : pluginRows) {
                    statuses.add(marketService.resolveStatus(p));
                }
                statusRows = List.copyOf(statuses);
            }
            fireTableDataChanged();
        }

        CatalogPlugin getPlugin(final int row) {
            return row >= 0 && row < pluginRows.size() ? pluginRows.get(row) : null;
        }

        PluginMarketStatus getStatus(final int row) {
            return row >= 0 && row < statusRows.size() ? statusRows.get(row) : null;
        }

        @Override
        public int getRowCount() {
            return pluginRows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            final CatalogPlugin p = pluginRows.get(rowIndex);
            final PluginMarketStatus st = statusRows.get(rowIndex);
            final CatalogPluginLocalized loc = PluginMarketService.localizePlugin(p, currentLocale);

            return switch (columnIndex) {
                case 0 -> loc.name();
                case 1 -> st.targetRelease().map(CatalogRelease::version).orElse("-");
                case 2 -> st.targetRelease().map(CatalogRelease::category).orElse("-");
                case 3 -> p.author();
                case 4 -> p.isOfficial() ? i18n.text("market.official") : i18n.text("market.third-party");
                case 5 -> formatStatusText(st);
                default -> "";
            };
        }
    }
}
