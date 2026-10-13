package dev.turboism.shell;

import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsSnapshot;
import dev.turboism.ui.settings.SettingsContributionStore;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;

/**
 * Dedicated window providing a unified space for all plugin-contributed settings,
 * listing contributing plugins on the left and their settings pages on the right.
 */
final class PluginSettingsWindow {

    @FunctionalInterface
    interface ControlRenderer {
        BooleanSupplier render(JDialog owner, JPanel panel, int row, SettingsControl control);
    }

    private final PluginLocalization i18n;
    private final SettingsContributionStore settingsStore;
    private final CorePluginManagement plugins;
    private final ControlRenderer controlRenderer;
    private JDialog dialog;

    PluginSettingsWindow(
            final PluginLocalization i18n,
            final SettingsContributionStore settingsStore,
            final CorePluginManagement plugins,
            final ControlRenderer controlRenderer) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.settingsStore = settingsStore;
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.controlRenderer = Objects.requireNonNull(controlRenderer, "controlRenderer");
    }

    void show(final Optional<String> initialPluginId) {
        CoreDialogs.onEdt(() -> {
            if (dialog != null) {
                dialog.dispose();
            }
            dialog = createDialog(initialPluginId);
            CoreDialogs.show(dialog);
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

    private JDialog createDialog(final Optional<String> initialPluginId) {
        final JDialog win = CoreDialogs.create(i18n.text("plugin-settings.title"), 840, 560);
        win.setLayout(new BorderLayout(8, 8));

        final List<String> contributingIds = settingsStore != null ? settingsStore.contributingPluginIds() : List.of();

        if (contributingIds.isEmpty()) {
            final JPanel emptyPanel = new JPanel(new BorderLayout());
            final JLabel emptyLabel = new JLabel(i18n.text("plugin-settings.no-plugins"), JLabel.CENTER);
            emptyPanel.add(emptyLabel, BorderLayout.CENTER);

            final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            final JButton closeButton = new JButton(i18n.text("common.close"));
            closeButton.addActionListener(e -> win.setVisible(false));
            buttonPanel.add(closeButton);

            win.add(emptyPanel, BorderLayout.CENTER);
            win.add(buttonPanel, BorderLayout.SOUTH);
            return win;
        }

        final Map<String, String> namesById = new HashMap<>();
        try {
            for (CorePluginManagement.PluginInfo info : plugins.plugins()) {
                namesById.put(info.id(), info.name());
            }
        } catch (RuntimeException ignored) {
        }

        final DefaultListModel<PluginEntry> listModel = new DefaultListModel<>();
        for (String id : contributingIds) {
            final String displayName = namesById.getOrDefault(id, id);
            listModel.addElement(new PluginEntry(id, displayName));
        }

        final JList<PluginEntry> pluginList = new JList<>(listModel);
        pluginList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pluginList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PluginEntry entry) {
                    setText(entry.name);
                    setToolTipText(entry.id);
                }
                setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                return this;
            }
        });

        final CardLayout cardLayout = new CardLayout();
        final JPanel cards = new JPanel(cardLayout);
        final List<BooleanSupplier> allSaves = new ArrayList<>();

        for (String id : contributingIds) {
            final List<SettingsSnapshot.Tab> tabs = settingsStore.snapshotForPlugin(id);
            final JPanel pluginPanel = renderPluginTabs(win, tabs, allSaves);
            cards.add(pluginPanel, id);
        }

        pluginList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                final PluginEntry selected = pluginList.getSelectedValue();
                if (selected != null) {
                    cardLayout.show(cards, selected.id);
                }
            }
        });

        int initialIndex = 0;
        if (initialPluginId.isPresent()) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).id.equals(initialPluginId.get())) {
                    initialIndex = i;
                    break;
                }
            }
        }
        pluginList.setSelectedIndex(initialIndex);

        final JScrollPane listScroll = new JScrollPane(pluginList);
        listScroll.setPreferredSize(new Dimension(240, 400));
        listScroll.setMinimumSize(new Dimension(180, 200));

        final JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, cards);
        splitPane.setDividerLocation(240);
        splitPane.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton okButton = new JButton(i18n.text("common.ok"));
        final JButton cancelButton = new JButton(i18n.text("common.cancel"));
        final JButton applyButton = new JButton(i18n.text("common.apply"));

        final Runnable saveAction = () -> {
            for (BooleanSupplier save : allSaves) {
                save.getAsBoolean();
            }
        };

        okButton.addActionListener(e -> {
            saveAction.run();
            win.setVisible(false);
        });
        cancelButton.addActionListener(e -> win.setVisible(false));
        applyButton.addActionListener(e -> saveAction.run());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyButton);

        win.add(splitPane, BorderLayout.CENTER);
        win.add(buttonPanel, BorderLayout.SOUTH);
        return win;
    }

    private JPanel renderPluginTabs(
            final JDialog owner, final List<SettingsSnapshot.Tab> tabs, final List<BooleanSupplier> saves) {
        if (tabs.isEmpty()) {
            final JPanel empty = new JPanel(new BorderLayout());
            empty.add(new JLabel(i18n.text("plugin-settings.no-plugins"), JLabel.CENTER));
            return empty;
        }

        if (tabs.size() == 1) {
            final SettingsSnapshot.Tab tab = tabs.get(0);
            final JPanel form = form();
            int row = 0;
            for (SettingsSnapshot.Entry entry : tab.contributions()) {
                saves.add(controlRenderer.render(
                        owner, form, row++, entry.contribution().control()));
            }
            final JScrollPane scroll = new JScrollPane(form);
            scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            final JPanel container = new JPanel(new BorderLayout());
            container.add(scroll, BorderLayout.CENTER);
            return container;
        }

        final JTabbedPane tabbedPane = new JTabbedPane();
        for (SettingsSnapshot.Tab tab : tabs) {
            final JPanel form = form();
            int row = 0;
            for (SettingsSnapshot.Entry entry : tab.contributions()) {
                saves.add(controlRenderer.render(
                        owner, form, row++, entry.contribution().control()));
            }
            final JScrollPane scroll = new JScrollPane(form);
            scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            tabbedPane.addTab(tab.title(), scroll);
        }
        final JPanel container = new JPanel(new BorderLayout());
        container.add(tabbedPane, BorderLayout.CENTER);
        return container;
    }

    private static JPanel form() {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        return panel;
    }

    private static void grid(final JPanel panel, final int row, final JComponent component) {
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(4, 4, 4, 4);
        panel.add(component, gbc);
    }

    private static void grid(final JPanel panel, final int row, final JLabel label, final JComponent component) {
        final GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.gridy = row;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(4, 4, 4, 8);
        panel.add(label, labelGbc);

        final GridBagConstraints compGbc = new GridBagConstraints();
        compGbc.gridx = 1;
        compGbc.gridy = row;
        compGbc.anchor = GridBagConstraints.WEST;
        compGbc.fill = GridBagConstraints.HORIZONTAL;
        compGbc.weightx = 1.0;
        compGbc.insets = new Insets(4, 0, 4, 4);
        panel.add(component, compGbc);
    }

    private record PluginEntry(String id, String name) {}
}
