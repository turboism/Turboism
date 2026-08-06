package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskRecordAdapter;
import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 多层同心扇区图视图：三类节点（纯蒙版 | 既是也用 | 纯使用者或无关）各占
 * 120° 扇区，类内节点从内圈到外圈逐层填充（中心不空、整体仍为圆形），
 * 避免两排节点横向溢出。
 * 有向箭头 mask -> user（inverted 红色）；图例 5 色；选中 GUID 节点描边高亮；
 * 点击节点回调 {@code onNodeClick}（复制 GUID 由窗口负责）。
 */
final class GraphPanel extends JComponent {

    static final double MIN_SCALE = 0.2;
    static final double MAX_SCALE = 4.0;

    private static final int NODE_RADIUS = 22;
    /** 圆半径下限：节点再少也保持可读的圆周大小。 */
    private static final int MIN_RADIUS = 160;
    /** 相邻节点沿弧的间距（含直径与间隙，gap ≈ 一个半径），决定各层半径与容量。 */
    private static final int NODE_SPACING = NODE_RADIUS * 3;
    /** 每类扇区弧长：120°（2π/3）。 */
    private static final double SECTOR_ARC = Math.PI * 2 / 3;
    private static final int MARGIN = 40;

    private final ClipMaskViewerState state;
    private final PluginLocalization localization;
    private final Consumer<String> onNodeClick;
    private boolean showUnrelated;
    private Set<String> highlightedGuids = Set.of();
    private final List<NodeBox> nodes = new ArrayList<>();
    private final Map<String, NodeBox> nodesByGuid = new HashMap<>();
    /** 过滤文本：非空/非空白时图只显示匹配节点及其直接邻居。 */
    private String filter = "";
    /** 单击选中的节点 GUID；与编辑器选中高亮（highlightedGuids）并存，用户选中优先。 */
    private String selectedGuid;
    private NodeBox dragNode;
    private int dragNodeStartX;
    private int dragNodeStartY;
    private double scale = 1.0;
    private Consumer<Double> viewScaleListener;
    private int offsetX;
    private int offsetY;
    private Point pressPoint;
    private int pressOffsetX;
    private int pressOffsetY;
    private boolean dragMoved;

    GraphPanel(
        final ClipMaskViewerState state,
        final PluginLocalization localization,
        final Consumer<String> onNodeClick
    ) {
        this.state = state;
        this.localization = localization;
        this.onNodeClick = onNodeClick;
        setBackground(Color.WHITE);
        setOpaque(true);
        rebuild();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                pressPoint = event.getPoint();
                pressOffsetX = offsetX;
                pressOffsetY = offsetY;
                dragMoved = false;
                final NodeBox hit = findNode(event.getPoint());
                if (hit != null && hit.record != null) {
                    // 命中节点：选中 + 记录节点拖动上下文（节点起始逻辑坐标 + press 屏幕点）。
                    selectedGuid = hit.record.guid();
                    repaint();
                    dragNode = hit;
                    dragNodeStartX = hit.x;
                    dragNodeStartY = hit.y;
                } else {
                    dragNode = null;
                }
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                pressPoint = null;
                dragNode = null;
            }

            @Override
            public void mouseClicked(final MouseEvent event) {
                if (event.getClickCount() == 2) {
                    resetView();
                    return;
                }
                if (dragMoved) {
                    return;
                }
                final NodeBox hit = findNode(event.getPoint());
                if (hit != null && hit.record != null) {
                    onNodeClick.accept(hit.record.guid());
                }
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent event) {
                final NodeBox hit = findNode(event.getPoint());
                setCursor(hit == null
                    ? Cursor.getDefaultCursor()
                    : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(hit == null ? null : hit.tooltip());
            }

            @Override
            public void mouseDragged(final MouseEvent event) {
                final Point current = event.getPoint();
                if (pressPoint != null) {
                    final double dx = current.x - pressPoint.x;
                    final double dy = current.y - pressPoint.y;
                    dragMoved |= dx * dx + dy * dy > 4 * 4;
                    if (dragNode != null) {
                        // 节点拖动：屏幕位移经逆变换（/scale）转逻辑位移，移动节点本身。
                        dragNode.setPosition(
                            dragNodeStartX + (int) Math.round(dx / scale),
                            dragNodeStartY + (int) Math.round(dy / scale));
                        repaint();
                    } else {
                        offsetX = pressOffsetX + (int) Math.round(dx);
                        offsetY = pressOffsetY + (int) Math.round(dy);
                        repaint();
                    }
                }
            }
        });
        addMouseWheelListener(event -> {
            final double factor = event.getWheelRotation() < 0 ? 1.2 : 1 / 1.2;
            zoomAt(event.getPoint(), factor);
        });
    }

    void zoomAt(final Point anchor, final double factor) {
        final double next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        if (next == scale) {
            return;
        }
        offsetX = (int) Math.round(anchor.x - (anchor.x - offsetX) * next / scale);
        offsetY = (int) Math.round(anchor.y - (anchor.y - offsetY) * next / scale);
        scale = next;
        repaint();
        notifyViewScaleListener();
    }

    /** 以当前面板视口中心为锚点设置缩放（clamp 到 [MIN_SCALE, MAX_SCALE]）。 */
    void setViewScale(final double newScale) {
        final double next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (next == scale) {
            return;
        }
        final double centerX = getWidth() / 2.0;
        final double centerY = getHeight() / 2.0;
        offsetX = (int) Math.round(centerX - (centerX - offsetX) * next / scale);
        offsetY = (int) Math.round(centerY - (centerY - offsetY) * next / scale);
        scale = next;
        repaint();
        notifyViewScaleListener();
    }

    /** 单个可替换的 scale 变化通知；传 null 取消。 */
    void setViewScaleListener(final Consumer<Double> listener) {
        this.viewScaleListener = listener;
    }

    private void notifyViewScaleListener() {
        if (viewScaleListener != null) {
            viewScaleListener.accept(scale);
        }
    }

    void panBy(final int dx, final int dy) {
        offsetX += dx;
        offsetY += dy;
        repaint();
    }

    void resetView() {
        final boolean scaleChanged = scale != 1.0;
        scale = 1.0;
        offsetX = 0;
        offsetY = 0;
        repaint();
        if (scaleChanged) {
            notifyViewScaleListener();
        }
    }

    double scale() { return scale; }

    int offsetX() { return offsetX; }

    int offsetY() { return offsetY; }

    void setShowUnrelated(final boolean show) {
        this.showUnrelated = show;
    }

    void setHighlightedGuids(final Set<String> guids) {
        this.highlightedGuids = guids == null ? Set.of() : Set.copyOf(guids);
        repaint();
    }

    /** 过滤：null→""；仅显示名称或 id 匹配的节点及其直接邻居（不重置视口）。 */
    void setFilter(final String value) {
        final String next = value == null ? "" : value;
        if (next.equals(filter)) {
            return;
        }
        this.filter = next;
        rebuild();
    }

    /** 选中节点（null 清除）；与编辑器高亮并存，绘制时用户选中优先。 */
    void setSelected(final String guid) {
        this.selectedGuid = guid;
        repaint();
    }

    /** 测试观察口：当前选中节点 + 直接邻居（含选中节点自身）。 */
    Set<String> selectionHighlightGuids() {
        return selectionSet();
    }

    /** 测试观察口：与选中节点相连的边的端点集（面板内）。 */
    Set<String> selectionEdgeEndpoints() {
        if (selectedGuid == null) {
            return Set.of();
        }
        final Set<String> endpoints = new LinkedHashSet<>();
        for (NodeBox user : nodes) {
            final ClipMaskRecord record = user.record;
            if (record == null) {
                continue;
            }
            for (String maskGuid : record.orderedMaskGuids()) {
                if (nodesByGuid.containsKey(maskGuid)
                    && (maskGuid.equals(selectedGuid) || record.guid().equals(selectedGuid))) {
                    endpoints.add(maskGuid);
                    endpoints.add(record.guid());
                }
            }
        }
        return endpoints;
    }

    NodeBox findNode(final Point point) {
        final double lx = (point.x - offsetX) / scale;
        final double ly = (point.y - offsetY) / scale;
        for (NodeBox node : nodes) {
            final double dx = lx - node.x;
            final double dy = ly - node.y;
            if (dx * dx + dy * dy <= NODE_RADIUS * NODE_RADIUS) {
                return node;
            }
        }
        return null;
    }

    void rebuild() {
        nodes.clear();
        nodesByGuid.clear();
        final List<ClipMaskRecord> pool = showUnrelated ? state.records() : state.filterRelated();
        // 过滤节点集：匹配（displayName/id，绝不读 guid）+ 直接邻居（指向的 mask + 以它为 mask 的使用者）。
        final List<ClipMaskRecord> effective = filter.isBlank() ? pool : filterWithNeighbors();
        if (selectedGuid != null && !containsGuid(effective, selectedGuid)) {
            // 过滤/重建后选中节点不在有效集 → 清空选中。
            selectedGuid = null;
        }
        if (effective == null || effective.isEmpty()) {
            resetView();
            setPreferredSize(new Dimension(400, 200));
            revalidate();
            return;
        }
        final Set<String> referencedAsMask = state.maskUsers().keySet();
        final List<ClipMaskRecord> top = new ArrayList<>();
        final List<ClipMaskRecord> middle = new ArrayList<>();
        final List<ClipMaskRecord> bottom = new ArrayList<>();
        for (ClipMaskRecord record : effective) {
            if (record == null) {
                continue;
            }
            final boolean isMask = referencedAsMask.contains(record.guid());
            final boolean hasMask = record.hasMasks();
            if (isMask && !hasMask) {
                top.add(record);
            } else if (isMask) {
                middle.add(record);
            } else {
                bottom.add(record);
            }
        }
        // 多层同心扇区布局：三类各占 120° 扇区（top 90°→210°、middle 210°→330°、
        // bottom 330°→90°），类内节点从内圈到外圈逐层填充——中心不再空旷、整体仍为圆形；
        // 每层容量 = max(1, floor(r * SECTOR_ARC / NODE_SPACING))，填满一层才进入下一层，
        // 总半径只随最深的类增长。每类 ≤ 一层容量时退化为单圈。
        int maxLayers = 0;
        maxLayers = Math.max(maxLayers, layerCount(top.size()));
        maxLayers = Math.max(maxLayers, layerCount(middle.size()));
        maxLayers = Math.max(maxLayers, layerCount(bottom.size()));
        final int radius = MIN_RADIUS + (maxLayers - 1) * NODE_SPACING;
        final int size = (radius + MARGIN) * 2;
        final int center = size / 2;
        layoutSector(top, 90, center, center);
        layoutSector(middle, 210, center, center);
        layoutSector(bottom, 330, center, center);
        setPreferredSize(new Dimension(size, size));
        revalidate();
    }

    /** 某类节点在扇区内逐层填满所需的层数（0 节点 → 0 层）。 */
    private static int layerCount(final int nodeCount) {
        if (nodeCount <= 0) {
            return 0;
        }
        int remaining = nodeCount;
        int layer = 0;
        while (remaining > 0) {
            remaining -= layerCapacity(layer);
            layer++;
        }
        return layer;
    }

    /** 第 layer 层（0 起）弧上可容纳的节点数：max(1, floor(r * SECTOR_ARC / NODE_SPACING))。 */
    private static int layerCapacity(final int layer) {
        final double radius = MIN_RADIUS + layer * NODE_SPACING;
        return Math.max(1, (int) Math.floor(radius * SECTOR_ARC / NODE_SPACING));
    }

    /** 把 group 从内圈到外圈逐层填充在 [startDeg, startDeg + 120)° 的扇区内（屏幕坐标 y 向下）。 */
    private void layoutSector(
        final List<ClipMaskRecord> group,
        final int startDeg,
        final int cx,
        final int cy
    ) {
        final int count = group.size();
        if (count == 0) {
            return;
        }
        final double start = Math.toRadians(startDeg);
        int placed = 0;
        int layer = 0;
        while (placed < count) {
            final int capacity = layerCapacity(layer);
            final double radius = MIN_RADIUS + layer * NODE_SPACING;
            final int take = Math.min(capacity, count - placed);
            final double step = SECTOR_ARC / take;
            for (int i = 0; i < take; i++) {
                final double angle = start + step * (i + 0.5);
                final ClipMaskRecord record = group.get(placed++);
                final NodeBox box = new NodeBox(record,
                    cx + (int) Math.round(radius * Math.cos(angle)),
                    cy + (int) Math.round(radius * Math.sin(angle)));
                nodes.add(box);
                nodesByGuid.put(record.guid(), box);
            }
            layer++;
        }
    }

    /** 测试观察口：各节点布局圆心（逻辑坐标，未含缩放/平移）。 */
    List<Point> nodeCenters() {
        final List<Point> centers = new ArrayList<>(nodes.size());
        for (NodeBox node : nodes) {
            centers.add(new Point(node.x, node.y));
        }
        return centers;
    }

    /** 测试观察口：当前面板节点 GUID 列表（布局顺序）。 */
    List<String> nodeGuids() {
        final List<String> guids = new ArrayList<>(nodes.size());
        for (NodeBox node : nodes) {
            if (node.record != null) {
                guids.add(node.record.guid());
            }
        }
        return guids;
    }

    /** 过滤匹配（只读 displayName 与 id，绝不读 guid）+ 直接邻居。 */
    private List<ClipMaskRecord> filterWithNeighbors() {
        final String needle = filter.toLowerCase(Locale.ROOT);
        final List<ClipMaskRecord> matches = new ArrayList<>();
        final Set<String> matchGuids = new LinkedHashSet<>();
        for (ClipMaskRecord record : state.records()) {
            if (record == null) {
                continue;
            }
            // 匹配判定只读 displayName 与 id，绝不读 guid。
            if (containsIgnoreCase(record.displayName(), needle)
                || containsIgnoreCase(record.id(), needle)) {
                matches.add(record);
                matchGuids.add(record.guid());
            }
        }
        final LinkedHashSet<ClipMaskRecord> result = new LinkedHashSet<>(matches);
        for (ClipMaskRecord record : matches) {
            // 匹配节点指向的 mask。
            for (String maskGuid : record.orderedMaskGuids()) {
                final ClipMaskRecord mask = state.byGuid().get(maskGuid);
                if (mask != null) {
                    result.add(mask);
                }
            }
        }
        for (ClipMaskRecord record : state.records()) {
            // 以匹配节点为 mask 的使用者（被指向）。
            if (record == null) {
                continue;
            }
            for (String maskGuid : record.orderedMaskGuids()) {
                if (matchGuids.contains(maskGuid)) {
                    result.add(record);
                    break;
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** 选中节点 + 直接邻居（按 nodesByGuid 解析，缺失忽略）。 */
    private Set<String> selectionSet() {
        final Set<String> result = new LinkedHashSet<>();
        if (selectedGuid == null || !nodesByGuid.containsKey(selectedGuid)) {
            return result;
        }
        result.add(selectedGuid);
        final ClipMaskRecord selected = nodesByGuid.get(selectedGuid).record;
        // 它指向的 mask。
        for (String maskGuid : selected.orderedMaskGuids()) {
            if (nodesByGuid.containsKey(maskGuid)) {
                result.add(maskGuid);
            }
        }
        // 以它为 mask 的使用者。
        for (ClipMaskRecord user : state.maskUsers().getOrDefault(selectedGuid, List.of())) {
            if (user != null && nodesByGuid.containsKey(user.guid())) {
                result.add(user.guid());
            }
        }
        return result;
    }

    private static boolean containsIgnoreCase(final String text, final String needleLower) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static boolean containsGuid(final List<ClipMaskRecord> records, final String guid) {
        for (ClipMaskRecord record : records) {
            if (record != null && guid.equals(record.guid())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // 图例与缩放指示在变换前绘制（屏幕坐标，固定左上角）。
            drawLegend(g2);
            g2.setColor(new Color(130, 130, 130));
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            g2.drawString(String.format("%.0f%%", scale * 100), 10, 36);

            g2.translate(offsetX, offsetY);
            g2.scale(scale, scale);

            g2.setStroke(new BasicStroke(1.2f));
            for (NodeBox user : nodes) {
                final ClipMaskRecord record = user.record;
                if (record == null) {
                    continue;
                }
                for (String maskGuid : record.orderedMaskGuids()) {
                    final NodeBox mask = nodesByGuid.get(maskGuid);
                    if (mask == null) {
                        continue;
                    }
                    final boolean touchesSelection = selectedGuid != null
                        && (maskGuid.equals(selectedGuid) || record.guid().equals(selectedGuid));
                    g2.setColor(touchesSelection
                        ? new Color(220, 40, 40)
                        : (record.inverted()
                            ? new Color(200, 80, 80, 160)
                            : new Color(80, 120, 200, 180)));
                    g2.setStroke(touchesSelection ? new BasicStroke(2.5f) : new BasicStroke(1.2f));
                    drawArrow(g2, mask.x, mask.y, user.x, user.y, NODE_RADIUS);
                }
            }

            // 用户选中高亮集（选中节点 + 直接邻居），节点描边红色覆盖编辑器橙色。
            final Set<String> selectionSet = selectionSet();
            final FontMetrics fm = g2.getFontMetrics();
            for (NodeBox node : nodes) {
                final ClipMaskRecord record = node.record;
                final boolean isMask = state.maskUsers().containsKey(record.guid());
                final boolean hasMask = record.hasMasks();
                final Color fill = resolveFill(isMask, hasMask, record.inverted());
                g2.setColor(fill);
                g2.fillOval(node.x - NODE_RADIUS, node.y - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);
                final boolean inSelection = selectionSet.contains(record.guid());
                final boolean highlighted = highlightedGuids.contains(record.guid());
                g2.setColor(inSelection
                    ? new Color(220, 40, 40)
                    : (highlighted ? new Color(230, 120, 20) : new Color(60, 60, 60)));
                g2.setStroke(new BasicStroke(inSelection || highlighted ? 3.2f : 1.4f));
                g2.drawOval(node.x - NODE_RADIUS, node.y - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);

                final String name = safeShort(record.displayName(), 10);
                final int textWidth = fm.stringWidth(name);
                g2.setColor(Color.BLACK);
                g2.drawString(name, node.x - textWidth / 2, node.y + fm.getAscent() / 2 - 2);

                final String countLabel = localization.format("node.mask.count", record.orderedMaskGuids().size())
                    + (record.inverted() ? localization.text("node.inverted") : "");
                final int countWidth = fm.stringWidth(countLabel);
                g2.setColor(new Color(80, 80, 80));
                g2.drawString(countLabel, node.x - countWidth / 2, node.y + NODE_RADIUS + fm.getAscent() + 2);
            }
        } finally {
            g2.dispose();
        }
    }

    private static Color resolveFill(final boolean isMask, final boolean hasMask, final boolean inverted) {
        if (isMask && hasMask) {
            return new Color(255, 230, 180);
        }
        if (isMask) {
            return new Color(210, 240, 210);
        }
        if (hasMask) {
            // inverted=cyan（浅青，与蓝使用者可区分）。
            return inverted ? new Color(170, 230, 235) : new Color(210, 225, 255);
        }
        return new Color(235, 235, 235);
    }

    private void drawLegend(final Graphics2D g2) {
        final String[] labels = {
            localization.text("legend.user"),
            localization.text("legend.user.inverted"),
            localization.text("legend.pure.mask"),
            localization.text("legend.both"),
            localization.text("legend.unrelated")
        };
        final Color[] fills = {
            new Color(210, 225, 255),
            new Color(170, 230, 235),
            new Color(210, 240, 210),
            new Color(255, 230, 180),
            new Color(235, 235, 235)
        };
        final Font previous = g2.getFont();
        g2.setFont(previous.deriveFont(Font.PLAIN, 11f));
        final FontMetrics fm = g2.getFontMetrics();
        int x = 10;
        int y = 10;
        for (int i = 0; i < labels.length; i++) {
            final int radius = 8;
            final int cx = x + radius;
            final int cy = y + radius;
            g2.setColor(fills[i]);
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            g2.setColor(new Color(60, 60, 60));
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            g2.setColor(Color.BLACK);
            g2.drawString(labels[i], cx + radius + 4, cy + fm.getAscent() / 2 - 2);
            x += radius * 2 + fm.stringWidth(labels[i]) + 14;
        }
        g2.setFont(previous);
    }

    private static void drawArrow(
        final Graphics2D g2,
        final int x1,
        final int y1,
        final int x2,
        final int y2,
        final int radius
    ) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double length = Math.hypot(dx, dy);
        if (length < 1e-3) {
            return;
        }
        final double ux = dx / length;
        final double uy = dy / length;
        final int sx = (int) Math.round(x1 + ux * radius);
        final int sy = (int) Math.round(y1 + uy * radius);
        final int ex = (int) Math.round(x2 - ux * radius);
        final int ey = (int) Math.round(y2 - uy * radius);
        g2.drawLine(sx, sy, ex, ey);
        final double arrowHead = 8.0;
        final double arrowWidth = 4.0;
        final double bx = ex - ux * arrowHead;
        final double by = ey - uy * arrowHead;
        final int px1 = (int) Math.round(bx - uy * arrowWidth);
        final int py1 = (int) Math.round(by + ux * arrowWidth);
        final int px2 = (int) Math.round(bx + uy * arrowWidth);
        final int py2 = (int) Math.round(by - ux * arrowWidth);
        final Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1.0f));
        g2.fillPolygon(new int[] { ex, px1, px2 }, new int[] { ey, py1, py2 }, 3);
        g2.setStroke(old);
    }

    private static String safeShort(final String value, final int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    private final class NodeBox {
        final ClipMaskRecord record;
        int x;
        int y;

        NodeBox(final ClipMaskRecord record, final int x, final int y) {
            this.record = record;
            this.x = x;
            this.y = y;
        }

        void setPosition(final int x, final int y) {
            this.x = x;
            this.y = y;
        }

        String tooltip() {
            final StringBuilder sb = new StringBuilder("<html>");
            sb.append("<b>").append(ClipMaskRecordAdapter.escapeHtml(record.displayName())).append("</b>");
            if (record.id() != null && !record.id().isEmpty() && !record.id().equals(record.displayName())) {
                sb.append("<br/>").append(ClipMaskRecordAdapter.escapeHtml(record.id()));
            }
            sb.append("<br/>").append(localization.format(
                "tooltip.guid", ClipMaskRecordAdapter.shortGuid(record.guid())));
            sb.append("<br/>").append(localization.format(
                "tooltip.mask.count", record.orderedMaskGuids().size()));
            if (record.inverted()) {
                sb.append("<br/><span style='color:#c04040'>")
                    .append(localization.text("tooltip.inverted"))
                    .append("</span>");
            }
            sb.append("</html>");
            return sb.toString();
        }
    }
}
