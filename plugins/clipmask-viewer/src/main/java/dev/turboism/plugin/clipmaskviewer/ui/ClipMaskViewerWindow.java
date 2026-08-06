package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.ClipMaskViewerPlugin.WindowView;
import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 剪贴蒙版检查器窗口（JDialog，MODELESS，只读）。
 *
 * <p>三种模式：Graph / 表-蒙版 / 表-使用者；顶部统计；刷新重读 SDK 服务；
 * 只读选中桥接（编辑器选中 -> 高亮），点击图节点复制 GUID 并 notifyStatus。</p>
 */
public final class ClipMaskViewerWindow extends JDialog implements WindowView {

    private static final String MODE_GRAPH = "graph";
    private static final String MODE_TABLE_MASK = "tmask";
    private static final String MODE_TABLE_USER = "tuser";
    private static final String STATUS_NOTIFY_ID = "clipmask-viewer.copied";

    private final PluginLocalization localization;
    private final PluginContext context;
    private final PluginLogger logger;
    private final Runnable onClosed;
    private final ClipMaskViewerState state = new ClipMaskViewerState();

    private final JLabel countLabel;
    private final JLabel orderConflictLabel;
    private final GraphPanel graphPanel;
    private final ClipMaskTableModels.MaskPrimaryTableModel maskModel;
    private final ClipMaskTableModels.UserPrimaryTableModel userModel;
    private final JTable maskTable;
    private final JTable userTable;
    private final CardLayout cards;
    private final JPanel center;

    private Registration selectionRegistration;
    private final List<Registration> statusRegistrations = new ArrayList<>();

    public ClipMaskViewerWindow(
        final PluginLocalization localization,
        final PluginContext context,
        final Runnable onClosed
    ) {
        super((java.awt.Frame) null, localization.text("window.title"), false);
        this.localization = Objects.requireNonNull(localization, "localization");
        this.context = Objects.requireNonNull(context, "context");
        this.logger = context.logger();
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        final JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        final JPanel topBar = new JPanel(new BorderLayout());
        final JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        final JToggleButton btnGraph = new JToggleButton(localization.text("mode.graph"));
        final JToggleButton btnTableMask = new JToggleButton(localization.text("mode.table.mask"));
        final JToggleButton btnTableUser = new JToggleButton(localization.text("mode.table.user"));
        btnGraph.setSelected(true);
        final ButtonGroup group = new ButtonGroup();
        group.add(btnGraph);
        group.add(btnTableMask);
        group.add(btnTableUser);
        modePanel.add(btnGraph);
        modePanel.add(btnTableMask);
        modePanel.add(btnTableUser);
        modePanel.add(Box.createHorizontalStrut(10));
        final JButton refreshButton = new JButton(localization.text("action.refresh"));
        modePanel.add(refreshButton);
        final JCheckBox showUnrelated = new JCheckBox(localization.text("checkbox.show.unrelated"), false);
        modePanel.add(showUnrelated);

        final JPanel rightInfoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        orderConflictLabel = new JLabel();
        orderConflictLabel.setFont(orderConflictLabel.getFont().deriveFont(Font.PLAIN, 11f));
        orderConflictLabel.setForeground(new Color(150, 80, 20));
        countLabel = new JLabel();
        countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, 12f));
        rightInfoPanel.add(orderConflictLabel);
        rightInfoPanel.add(countLabel);

        topBar.add(modePanel, BorderLayout.WEST);
        topBar.add(rightInfoPanel, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        cards = new CardLayout();
        center = new JPanel(cards);
        graphPanel = new GraphPanel(state, localization, this::copyGuid);
        graphPanel.setShowUnrelated(false);
        maskModel = new ClipMaskTableModels.MaskPrimaryTableModel(state, localization);
        userModel = new ClipMaskTableModels.UserPrimaryTableModel(state, localization);
        maskTable = buildTable(maskModel);
        userTable = buildTable(userModel);

        center.add(new JScrollPane(graphPanel), MODE_GRAPH);
        center.add(new JScrollPane(maskTable), MODE_TABLE_MASK);
        center.add(new JScrollPane(userTable), MODE_TABLE_USER);
        root.add(center, BorderLayout.CENTER);

        final JPanel footer = new JPanel(new BorderLayout());
        final JLabel hint = new JLabel(localization.text("footer.hint"));
        hint.setForeground(new Color(100, 100, 100));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        final JButton closeButton = new JButton(localization.text("button.close"));
        closeButton.addActionListener(ignored -> dispose());
        // 缩放工具栏（仅图结构模式显示）：滑动条 / 百分比，与 GraphPanel 双向同步。
        final JPanel zoomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        // 过滤框（滑条左边）：按显示名称/ID 过滤，保留相邻节点；随 zoomBar 一起显隐。
        final JTextField filterField = new JTextField(14);
        filterField.setPreferredSize(new Dimension(140, filterField.getPreferredSize().height));
        filterField.setToolTipText(localization.text("filter.label"));
        final Runnable applyFilter = () -> graphPanel.setFilter(filterField.getText());
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent event) { applyFilter.run(); }
            @Override public void removeUpdate(final DocumentEvent event) { applyFilter.run(); }
            @Override public void changedUpdate(final DocumentEvent event) { applyFilter.run(); }
        });
        zoomBar.add(filterField);
        final JSlider zoomSlider = new JSlider(20, 400, 100);
        zoomSlider.setPaintTicks(true);
        zoomSlider.setMajorTickSpacing(20);
        zoomSlider.setPaintLabels(false);
        zoomSlider.setPreferredSize(new Dimension(160, zoomSlider.getPreferredSize().height));
        zoomSlider.setToolTipText(localization.text("zoom.slider"));
        final JLabel zoomPercentLabel = new JLabel("100%");
        zoomPercentLabel.setFont(zoomPercentLabel.getFont().deriveFont(Font.PLAIN, 11f));
        zoomBar.add(zoomSlider);
        zoomBar.add(zoomPercentLabel);
        footer.add(hint, BorderLayout.WEST);
        footer.add(zoomBar, BorderLayout.CENTER);
        footer.add(closeButton, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        // 拖动时实时生效（getValueIsAdjusting 阶段同样触发 ChangeListener）。
        zoomSlider.addChangeListener(event -> {
            final double target = zoomSlider.getValue() / 100.0;
            if (Math.abs(target - graphPanel.scale()) < 1e-9) {
                return; // 递归 guard：listener 回写 slider 时值相同，直接短路。
            }
            graphPanel.setViewScale(target);
        });
        graphPanel.setViewScaleListener(scale -> {
            zoomSlider.setValue((int) Math.round(scale * 100));
            zoomPercentLabel.setText(String.format("%.0f%%", scale * 100));
        });

        btnGraph.addActionListener(ignored -> {
            cards.show(center, MODE_GRAPH);
            zoomBar.setVisible(true);
        });
        btnTableMask.addActionListener(ignored -> {
            cards.show(center, MODE_TABLE_MASK);
            zoomBar.setVisible(false);
        });
        btnTableUser.addActionListener(ignored -> {
            cards.show(center, MODE_TABLE_USER);
            zoomBar.setVisible(false);
        });
        refreshButton.addActionListener(ignored -> refresh());
        showUnrelated.addActionListener(ignored -> {
            graphPanel.setShowUnrelated(showUnrelated.isSelected());
            graphPanel.rebuild();
            graphPanel.repaint();
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(final WindowEvent event) {
                release();
                onClosed.run();
            }
        });

        setContentPane(root);
        setSize(new Dimension(900, 620));
        setLocationByPlatform(true);
    }

    @Override
    public void showAndFront() {
        setVisible(true);
        toFront();
    }

    @Override
    public void refresh() {
        try {
            state.refreshData(context.cubismClipMasks());
        } catch (RuntimeException failure) {
            logger.warn("Clip Mask Viewer refresh failed safely: " + failure.getMessage());
            state.clear();
        }
        updateTopInfo();
        graphPanel.rebuild();
        graphPanel.repaint();
        maskModel.fireRefresh();
        userModel.fireRefresh();
        applyHighlight(readHighlightedGuids());
    }

    @Override
    public void dispose() {
        super.dispose();
        release();
        onClosed.run();
    }

    private void release() {
        final Registration selection = selectionRegistration;
        selectionRegistration = null;
        if (selection != null) {
            selection.close();
        }
        for (Registration status : statusRegistrations) {
            status.close();
        }
        statusRegistrations.clear();
    }

    private void updateTopInfo() {
        countLabel.setText(localization.format(
            "stats.summary",
            state.countUniqueMasks(),
            state.records().size(),
            state.countWithMasks()
        ));
        final int conflicts = state.countOrderConflicts();
        orderConflictLabel.setText(conflicts > 0
            ? localization.format("stats.order.conflicts", conflicts)
            : "");
    }

    // ── 选中桥接（只读）──────────────────────────────────────────────

    private Set<String> readHighlightedGuids() {
        try {
            final SelectionSnapshot selection = context.cubismRead().selection();
            final Set<String> guids = new LinkedHashSet<>(selection.selectedObjectIds());
            selection.activeArtMeshId().ifPresent(guids::add);
            return guids;
        } catch (RuntimeException unavailable) {
            return Set.of();
        }
    }

    private void subscribeSelection() {
        try {
            selectionRegistration = context.selectionQuery().onSelectionChanged(event -> {
                final SelectionSummary summary = event.currentSelection();
                final Set<String> guids = new LinkedHashSet<>();
                summary.selectedArtMeshIds().forEach(id -> guids.add(id.value()));
                summary.selectedModelObjectIds().forEach(id -> guids.add(id.value()));
                SwingUtilities.invokeLater(() -> applyHighlight(guids));
            });
        } catch (dev.turboism.sdk.cubism.CubismServiceException | RuntimeException unavailable) {
            // selection 桥接不可用：不报错，仅不做高亮
        }
    }

    private void applyHighlight(final Set<String> guids) {
        graphPanel.setHighlightedGuids(guids);
        maskModel.setHighlightedGuids(guids);
        userModel.setHighlightedGuids(guids);
    }

    // ── 复制 GUID ───────────────────────────────────────────────────

    private void copyGuid(final String guid) {
        try {
            Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(guid), null);
            notifyStatus(localization.format("status.copied", guid));
        } catch (RuntimeException failure) {
            logger.warn("Clip Mask Viewer GUID copy failed safely: " + failure.getMessage());
        }
    }

    private void notifyStatus(final String message) {
        try {
            statusRegistrations.add(context.uiHost().notifyStatus(
                new StatusNotification(STATUS_NOTIFY_ID, "INFO", message)
            ));
        } catch (RuntimeException unavailable) {
            logger.warn("Clip Mask Viewer status notification unavailable");
        }
    }

    private JTable buildTable(final javax.swing.table.AbstractTableModel model) {
        final JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        final TableCellRenderer baseRenderer = table.getDefaultRenderer(Object.class);
        final DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                final JTable table,
                final Object value,
                final boolean isSelected,
                final boolean hasFocus,
                final int row,
                final int column
            ) {
                final Component component = baseRenderer.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
                if (!isSelected && model instanceof ClipMaskTableModels.MaskPrimaryTableModel mask
                    && mask.isRowHighlighted(row)) {
                    component.setBackground(new Color(255, 244, 200));
                } else if (!isSelected && model instanceof ClipMaskTableModels.UserPrimaryTableModel user
                    && user.isRowHighlighted(row)) {
                    component.setBackground(new Color(255, 244, 200));
                }
                return component;
            }
        };
        renderer.setVerticalAlignment(JLabel.TOP);
        table.getColumnModel().getColumn(0).setCellRenderer(renderer);
        table.getColumnModel().getColumn(1).setCellRenderer(renderer);
        table.getColumnModel().getColumn(2).setCellRenderer(renderer);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(560);
        return table;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (selectionRegistration == null) {
            subscribeSelection();
        }
    }
}
