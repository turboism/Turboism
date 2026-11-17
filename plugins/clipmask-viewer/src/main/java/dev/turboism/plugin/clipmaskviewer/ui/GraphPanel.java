package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.domain.ClipMaskRecordAdapter;
import dev.turboism.plugin.clipmaskviewer.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * Multi-layer concentric sector graph view. The three node classes, pure mask, both, and pure
 * user or unrelated, each occupy a 120° sector, and the nodes of a class fill layer by layer from
 * the inner ring outward, so the centre stays occupied and the shape stays circular while two
 * rows of nodes never overflow sideways. A directed arrow runs mask -> user and an inverted mask
 * is red, the legend holds 5 colors, a selected GUID node gets an outline highlight, and a node
 * click calls {@code onNodeClick}; the window copies the GUID.
 */
final class GraphPanel extends JComponent {

    static final double MIN_SCALE = 0.2;
    static final double MAX_SCALE = 4.0;

    private static final int NODE_RADIUS = 22;
    /** Lower bound of the circle radius, so even a few nodes keep a readable circumference. */
    private static final int MIN_RADIUS = 160;
    /** Arc spacing between adjacent nodes, which includes the diameter and the gap of about one radius and sets each layer radius and capacity. */
    private static final int NODE_SPACING = NODE_RADIUS * 3;
    /** Arc length of each class sector: 120°, which is 2π/3. */
    private static final double SECTOR_ARC = Math.PI * 2 / 3;

    private static final int MARGIN = 40;

    /** Toggleable classes, where the user and user.inverted legend items share the user class. */
    private static final List<String> CATEGORIES = List.of("user", "mask", "both", "unrelated");

    private final ClipMaskViewerState state;
    private final PluginLocalization localization;
    private final Consumer<String> onNodeClick;
    private boolean showUnrelated;
    private Set<String> highlightedGuids = Set.of();
    private final List<NodeBox> nodes = new ArrayList<>();
    private final Map<String, NodeBox> nodesByGuid = new HashMap<>();
    /** Filter text. A non-empty, non-blank value limits the graph to the matching nodes and their direct neighbors. */
    private String filter = "";
    /** Hidden classes, valued mask, user, both, or unrelated. A hidden class and its direct neighbors disappear, and the legend strikes the item through. */
    private final Set<String> hiddenCategories = new HashSet<>();
    /** Screen hit rectangle of each legend item in the last paint, in the 5-item order of the labels and fills arrays. */
    private final List<Rectangle> legendHitBounds = new ArrayList<>();
    /** GUID of the node selected by a click. It coexists with the editor highlight and takes priority. */
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
            final Consumer<String> onNodeClick) {
        this.state = state;
        this.localization = localization;
        this.onNodeClick = onNodeClick;
        setBackground(Color.WHITE);
        setOpaque(true);
        rebuild();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                // The legend lives in screen coordinates and wins the hit, so it toggles a class and never selects a
                // node or pans the canvas.
                for (int i = 0; i < legendHitBounds.size(); i++) {
                    if (legendHitBounds.get(i).contains(event.getPoint())) {
                        final String category = legendCategory(i);
                        setCategoryVisible(category, !categoryVisible(category));
                        return;
                    }
                }
                pressPoint = event.getPoint();
                pressOffsetX = offsetX;
                pressOffsetY = offsetY;
                dragMoved = false;
                final NodeBox hit = findNode(event.getPoint());
                if (hit != null && hit.record != null) {
                    // A node hit selects it and records the drag context: the start logical point and the press screen
                    // point.
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
                setCursor(hit == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
                        // A node drag converts the screen delta through the inverse scale into a logical delta and
                        // moves the node.
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

    /** Sets the zoom anchored at the current viewport centre and clamps it to [MIN_SCALE, MAX_SCALE]. */
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

    /** One replaceable scale-change listener, where null removes it. */
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

    double scale() {
        return scale;
    }

    int offsetX() {
        return offsetX;
    }

    int offsetY() {
        return offsetY;
    }

    void setShowUnrelated(final boolean show) {
        this.showUnrelated = show;
    }

    void setHighlightedGuids(final Set<String> guids) {
        this.highlightedGuids = guids == null ? Set.of() : Set.copyOf(guids);
        repaint();
    }

    /** Applies the filter, where null becomes an empty string. Only nodes whose name or id matches and their direct neighbors stay, and the viewport is not reset. */
    void setFilter(final String value) {
        final String next = value == null ? "" : value;
        if (next.equals(filter)) {
            return;
        }
        this.filter = next;
        rebuild();
    }

    /** Toggles a class, ignoring null and an unknown value. A change rebuilds the layout and repaints, and the existing rebuild clears a selected node that became hidden. */
    void setCategoryVisible(final String category, final boolean visible) {
        if (category == null || !CATEGORIES.contains(category)) {
            return;
        }
        final boolean changed = visible ? hiddenCategories.remove(category) : hiddenCategories.add(category);
        if (changed) {
            rebuild();
            repaint();
        }
    }

    boolean categoryVisible(final String category) {
        return !hiddenCategories.contains(category);
    }

    /** Test observation point: the currently hidden class set. */
    Set<String> hiddenCategories() {
        return new HashSet<>(hiddenCategories);
    }

    /** Selects a node, where null clears it. The selection coexists with the editor highlight and wins at paint time. */
    void setSelected(final String guid) {
        this.selectedGuid = guid;
        repaint();
    }

    /** Test observation point: the selected node and its direct neighbors, including the node itself. */
    Set<String> selectionHighlightGuids() {
        return selectionSet();
    }

    /** Test observation point: the endpoints of the edges attached to the selected node inside the panel. */
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
        // Class-visible set V: all nodes minus the hidden-class nodes and their direct neighbors, hidden as a block.
        final Set<String> visibleCategoryGuids = visibleCategoryGuids(pool);
        // Filter node set: a match on displayName or id, never on guid, plus the direct neighbors, which are the mask
        // it points to and the users it masks.
        // Combined with the class toggle by AND: the effective set is V ∩ (M ∪ N).
        final List<ClipMaskRecord> filterSet = filter.isBlank() ? pool : filterWithNeighbors();
        final List<ClipMaskRecord> effective = new ArrayList<>(filterSet.size());
        for (ClipMaskRecord record : filterSet) {
            if (record != null && visibleCategoryGuids.contains(record.guid())) {
                effective.add(record);
            }
        }
        if (selectedGuid != null && !containsGuid(effective, selectedGuid)) {
            // A selected node outside the effective set after a filter or rebuild is cleared.
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
        // Multi-layer concentric sector layout. The three classes occupy 120° sectors, top from 90° to 210°, middle
        // from 210° to 330°, and bottom from 330° to 90°, and the nodes of a class fill layer by layer from the inner
        // ring outward, so the centre stays occupied and the shape stays circular. A layer capacity is max(1, floor(r *
        // SECTOR_ARC / NODE_SPACING)), a full layer opens the next one, and the total radius grows only with the
        // deepest class. A class within one layer capacity degrades to a single ring.
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

    /** Layer count a class needs to fill its sector, where 0 nodes need 0 layers. */
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

    /** Node capacity of the arc at layer, counted from 0: max(1, floor(r * SECTOR_ARC / NODE_SPACING)). */
    private static int layerCapacity(final int layer) {
        final double radius = MIN_RADIUS + layer * NODE_SPACING;
        return Math.max(1, (int) Math.floor(radius * SECTOR_ARC / NODE_SPACING));
    }

    /** Fills a group layer by layer from the inner ring outward inside the [startDeg, startDeg + 120)° sector, with screen y pointing down. */
    private void layoutSector(final List<ClipMaskRecord> group, final int startDeg, final int cx, final int cy) {
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
                final NodeBox box = new NodeBox(
                        record,
                        cx + (int) Math.round(radius * Math.cos(angle)),
                        cy + (int) Math.round(radius * Math.sin(angle)));
                nodes.add(box);
                nodesByGuid.put(record.guid(), box);
            }
            layer++;
        }
    }

    /** Test observation point: the layout centre of each node in logical coordinates, without zoom or pan. */
    List<Point> nodeCenters() {
        final List<Point> centers = new ArrayList<>(nodes.size());
        for (NodeBox node : nodes) {
            centers.add(new Point(node.x, node.y));
        }
        return centers;
    }

    /** Test observation point: the node GUID list of the panel in layout order. */
    List<String> nodeGuids() {
        final List<String> guids = new ArrayList<>(nodes.size());
        for (NodeBox node : nodes) {
            if (node.record != null) {
                guids.add(node.record.guid());
            }
        }
        return guids;
    }

    /** Filter match on displayName and id, never on guid, plus the direct neighbors. */
    private List<ClipMaskRecord> filterWithNeighbors() {
        final String needle = filter.toLowerCase(Locale.ROOT);
        final List<ClipMaskRecord> matches = new ArrayList<>();
        final Set<String> matchGuids = new LinkedHashSet<>();
        for (ClipMaskRecord record : state.records()) {
            if (record == null) {
                continue;
            }
            // The match reads displayName and id only and never reads guid.
            if (containsIgnoreCase(record.displayName(), needle) || containsIgnoreCase(record.id(), needle)) {
                matches.add(record);
                matchGuids.add(record.guid());
            }
        }
        final LinkedHashSet<ClipMaskRecord> result = new LinkedHashSet<>(matches);
        for (ClipMaskRecord record : matches) {
            // The mask a matching node points to.
            for (String maskGuid : record.orderedMaskGuids()) {
                final ClipMaskRecord mask = state.byGuid().get(maskGuid);
                if (mask != null) {
                    result.add(mask);
                }
            }
        }
        for (ClipMaskRecord record : state.records()) {
            // The users that take a matching node as their mask.
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

    /** The selected node and its direct neighbors, resolved through nodesByGuid and skipping a missing node. */
    private Set<String> selectionSet() {
        final Set<String> result = new LinkedHashSet<>();
        if (selectedGuid == null || !nodesByGuid.containsKey(selectedGuid)) {
            return result;
        }
        result.add(selectedGuid);
        final ClipMaskRecord selected = nodesByGuid.get(selectedGuid).record;
        // The mask it points to.
        for (String maskGuid : selected.orderedMaskGuids()) {
            if (nodesByGuid.containsKey(maskGuid)) {
                result.add(maskGuid);
            }
        }
        // The users that take it as their mask.
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

    /** Class-visible set V: the pool nodes outside a hidden class that are not a direct neighbor of a hidden-class node, skipping a missing neighbor. */
    private Set<String> visibleCategoryGuids(final List<ClipMaskRecord> pool) {
        final Set<String> hidden = new LinkedHashSet<>();
        for (ClipMaskRecord record : pool) {
            if (record == null || !hiddenCategories.contains(categoryOf(record))) {
                continue;
            }
            hidden.add(record.guid());
            // Direct neighbors: the mask it points to and the users that take it as their mask.
            for (String maskGuid : record.orderedMaskGuids()) {
                hidden.add(maskGuid);
            }
            for (ClipMaskRecord user : state.maskUsers().getOrDefault(record.guid(), List.of())) {
                if (user != null) {
                    hidden.add(user.guid());
                }
            }
        }
        final Set<String> visible = new LinkedHashSet<>();
        for (ClipMaskRecord record : pool) {
            if (record != null && !hidden.contains(record.guid())) {
                visible.add(record.guid());
            }
        }
        return visible;
    }

    /** Maps a record to a class: a pure mask becomes mask, a mask that is also a user becomes both, a masked record becomes user, and the rest become unrelated. */
    private String categoryOf(final ClipMaskRecord record) {
        final boolean isMask = state.maskUsers().containsKey(record.guid());
        final boolean hasMask = record.hasMasks();
        if (isMask && !hasMask) {
            return "mask";
        }
        if (isMask) {
            return "both";
        }
        return hasMask ? "user" : "unrelated";
    }

    /** Maps the 5 legend items to classes: index 0 and 1 become user, including the inverted user, 2 becomes mask, 3 becomes both, and 4 becomes unrelated. */
    private static String legendCategory(final int index) {
        switch (index) {
            case 0:
            case 1:
                return "user";
            case 2:
                return "mask";
            case 3:
                return "both";
            default:
                return "unrelated";
        }
    }

    /** Test observation point: the screen hit rectangle of each legend item in the last paint, returned as an immutable copy when empty. */
    List<Rectangle> legendHitBounds() {
        return legendHitBounds.isEmpty() ? List.of() : new ArrayList<>(legendHitBounds);
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // The legend and the zoom indicator paint before the transform, in screen coordinates at a fixed top-left
            // corner.
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
                    g2.setColor(
                            touchesSelection
                                    ? new Color(220, 40, 40)
                                    : (record.inverted() ? new Color(200, 80, 80, 160) : new Color(80, 120, 200, 180)));
                    g2.setStroke(touchesSelection ? new BasicStroke(2.5f) : new BasicStroke(1.2f));
                    drawArrow(g2, mask.x, mask.y, user.x, user.y, NODE_RADIUS);
                }
            }

            // The user selection highlight set holds the selected node and its direct neighbors, and its red outline
            // covers the editor orange.
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
                g2.setColor(
                        inSelection
                                ? new Color(220, 40, 40)
                                : (highlighted ? new Color(230, 120, 20) : new Color(60, 60, 60)));
                g2.setStroke(new BasicStroke(inSelection || highlighted ? 3.2f : 1.4f));
                g2.drawOval(node.x - NODE_RADIUS, node.y - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);

                final String name = safeShort(record.displayName(), 10);
                final int textWidth = fm.stringWidth(name);
                g2.setColor(Color.BLACK);
                g2.drawString(name, node.x - textWidth / 2, node.y + fm.getAscent() / 2 - 2);

                final String countLabel = localization.format(
                                "node.mask.count", record.orderedMaskGuids().size())
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
            // An inverted mask is cyan, light enough to stay distinct from a blue user.
            return inverted ? new Color(170, 230, 235) : new Color(210, 225, 255);
        }
        return new Color(235, 235, 235);
    }

    private void drawLegend(final Graphics2D g2) {
        legendHitBounds.clear();
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
            final boolean hidden = hiddenCategories.contains(legendCategory(i));
            // A hidden class fades its swatch to light gray and strikes the text through.
            g2.setColor(hidden ? new Color(200, 200, 200) : fills[i]);
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            g2.setColor(new Color(60, 60, 60));
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
            g2.setColor(Color.BLACK);
            final int textX = cx + radius + 4;
            final int baseline = cy + fm.getAscent() / 2 - 2;
            g2.drawString(labels[i], textX, baseline);
            if (hidden) {
                // The strike-through crosses the middle of the text over stringWidth.
                final int midY = baseline - fm.getAscent() / 2 + 1;
                g2.drawLine(textX, midY, textX + fm.stringWidth(labels[i]), midY);
            }
            legendHitBounds.add(new Rectangle(x, y, radius * 2 + fm.stringWidth(labels[i]) + 14, radius * 2));
            x += radius * 2 + fm.stringWidth(labels[i]) + 14;
        }
        g2.setFont(previous);
    }

    private static void drawArrow(
            final Graphics2D g2, final int x1, final int y1, final int x2, final int y2, final int radius) {
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
        g2.fillPolygon(new int[] {ex, px1, px2}, new int[] {ey, py1, py2}, 3);
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
            sb.append("<b>")
                    .append(ClipMaskRecordAdapter.escapeHtml(record.displayName()))
                    .append("</b>");
            if (record.id() != null && !record.id().isEmpty() && !record.id().equals(record.displayName())) {
                sb.append("<br/>").append(ClipMaskRecordAdapter.escapeHtml(record.id()));
            }
            sb.append("<br/>")
                    .append(localization.format("tooltip.guid", ClipMaskRecordAdapter.shortGuid(record.guid())));
            sb.append("<br/>")
                    .append(localization.format(
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
