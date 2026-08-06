package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GraphPanelTest {

    private static final int LEGACY_ROW_WIDTH_ESTIMATE = 80 + 60 * 80;

    @Test
    void circularLayoutKeepsSquareAspectAndAvoidsRowOverflow() {
        final ClipMaskViewerState state = stateWith(20); // 60 节点（top 20 / middle 0 / bottom 40）
        final GraphPanel panel = new GraphPanel(state, localization(), clicked -> { });

        final Dimension size = panel.getPreferredSize();
        // 多层同心扇区布局：长宽比 1:1；同 60 节点下行布局需 ~4880px 宽，圆形显著收窄。
        assertTrue(Math.abs((double) size.width / size.height - 1.0) < 0.01,
            "expected square aspect, got " + size);
        assertTrue(size.width < LEGACY_ROW_WIDTH_ESTIMATE / 2,
            "circular width " + size.width + " must be well below row-layout width "
                + LEGACY_ROW_WIDTH_ESTIMATE);

        // 中心非空：存在内圈节点（距圆心 < 总半径（面板半径 = size/2）的一半；旧单圈布局所有节点都在最外圈）。
        final int center = size.width / 2;
        final double minDistance = panel.nodeCenters().stream()
            .mapToDouble(p -> Math.hypot(p.x - center, p.y - center))
            .min().orElse(Double.MAX_VALUE);
        assertTrue(minDistance < size.width / 4.0,
            "inner ring must fill the center area, min node distance " + minDistance);

        // 层数/容量正确：top 类 20 节点 → 3 层（容量 7+9+11，末层 4 个）；
        // bottom 类 40 节点 → 4 层恰好填满（7+9+11+13）。
        assertEquals(List.of(7, 9, 4), ringSizes(panel, 90, 210), "top sector layers");
        assertEquals(List.of(7, 9, 11, 13), ringSizes(panel, 330, 90), "bottom sector layers");
    }

    @Test
    void emptyStateKeepsLegacyEmptySize() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        final GraphPanel panel = new GraphPanel(state, localization(), clicked -> { });
        assertEquals(new Dimension(400, 200), panel.getPreferredSize());

        state.refreshData(service(record("user-1", "A", false, "mask-1")));
        panel.rebuild();
        assertEquals(400, panel.getPreferredSize().width);

        state.clear();
        panel.rebuild();
        assertEquals(new Dimension(400, 200), panel.getPreferredSize());
    }

    @Test
    void clickOnNodeInvokesCallbackAndCenterClickDoesNot() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service(
            record("user-1", "A", false, "mask-1"),
            record("mask-1", "M", false)));
        final List<String> clicked = new ArrayList<>();
        final GraphPanel panel = new GraphPanel(state, localization(), clicked::add);

        final Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        panel.setHighlightedGuids(Set.of("mask-1"));
        // 高亮不抛异常（回归）；中心点必然无节点（节点在圆周上）。
        dispatchClick(panel, size.width / 2, size.height / 2);
        assertTrue(clicked.isEmpty());

        // 网格扫描必然命中圆周上的节点（步长 < 节点直径）。
        for (int x = 0; x < size.width; x += 8) {
            for (int y = 0; y < size.height; y += 8) {
                dispatchClick(panel, x, y);
            }
        }
        assertTrue(clicked.contains("mask-1"), "node click must reach the guid callback");
        assertTrue(clicked.contains("user-1"), "node click must reach the guid callback");
    }

    @Test
    void paintRendersLegendEdgesAndNodesWithoutFailure() {
        final ClipMaskViewerState state = stateWith(6);
        final GraphPanel panel = new GraphPanel(state, localization(), clicked -> { });
        final Dimension size = panel.getPreferredSize();
        panel.setSize(size);
        final BufferedImage image =
            new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }


    void compactMultiRingLayoutShrinksTotalRadius() {
        final GraphPanel panel = new GraphPanel(stateWith(20), localization(), clicked -> { });
        final int width = panel.getPreferredSize().width;
        // 60 节点 → top 3 层、bottom 4 层（7+9+11+13）→ 总半径 = 160 + (4-1)*46 = 298。
        final int expectedRadius = 160 + 3 * (2 * 22 + 2);
        assertEquals((expectedRadius + 40) * 2, width);
        final int oldSingleRingRadius = (int) Math.round(60 * (2 * 22 + 8) / (2 * Math.PI));
        assertTrue(width < (oldSingleRingRadius + 40) * 2,
            "multi-ring layout must be tighter than the old single-ring +8 gap");
    }

    @Test
    void smallGroupsDegenerateToSingleRing() {
        final GraphPanel panel = new GraphPanel(stateWith(2), localization(), clicked -> { });
        final Dimension size = panel.getPreferredSize();
        // 每类 2 节点 ≤ 首层容量 7 → 单圈退化：半径 = MIN_RADIUS（160），尺寸与旧布局一致。
        assertEquals((160 + 40) * 2, size.width);
        final int center = size.width / 2;
        final Set<Integer> rings = new java.util.TreeSet<>();
        for (Point point : panel.nodeCenters()) {
            rings.add((int) Math.round(Math.hypot(point.x - center, point.y - center)));
        }
        assertEquals(Set.of(160), rings, "all nodes must sit on the single inner ring");
    }

    @Test
    void zoomKeepsCursorAnchorFixedAndClamps() {
        final GraphPanel panel = new GraphPanel(stateWith(2), localization(), clicked -> { });
        panel.zoomAt(new Point(100, 120), 1.2);
        assertEquals(1.2, panel.scale(), 1e-9);
        // 锚点不变性：光标 (100,120) 下的逻辑坐标缩放前后一致 → offset 按公式移动。
        assertEquals(-20, panel.offsetX());
        assertEquals(-24, panel.offsetY());

        for (int i = 0; i < 10; i++) {
            panel.zoomAt(new Point(200, 200), 1.2);
        }
        assertEquals(4.0, panel.scale(), 1e-9);
        final int ox = panel.offsetX();
        final int oy = panel.offsetY();
        panel.zoomAt(new Point(200, 200), 1.2);
        assertEquals(ox, panel.offsetX(), "clamped zoom-in must not shift the view");
        assertEquals(oy, panel.offsetY());

        for (int i = 0; i < 30; i++) {
            panel.zoomAt(new Point(200, 200), 1 / 1.2);
        }
        assertEquals(0.2, panel.scale(), 1e-9);
    }

    @Test
    void wheelEventZoomsInAroundCursor() {
        final GraphPanel panel = new GraphPanel(stateWith(2), localization(), clicked -> { });
        panel.dispatchEvent(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), 0, 100, 120, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1));
        assertEquals(1.2, panel.scale(), 1e-9);
        assertEquals(-20, panel.offsetX());
        assertEquals(-24, panel.offsetY());
    }

    @Test
    void panByShiftsViewportAndHitDetection() {
        final GraphPanel panel = new GraphPanel(stateWithUserAndMask(), localization(), clicked -> { });
        panel.panBy(30, -10);
        assertEquals(30, panel.offsetX());
        assertEquals(-10, panel.offsetY());
        assertNotNull(panel.findNode(new Point(339 + 30, 280 - 10)));
        assertNull(panel.findNode(new Point(339, 280)));
    }

    @Test
    void dragPansViewportAndSuppressesNodeClick() {
        final List<String> clicked = new ArrayList<>();
        final GraphPanel panel = new GraphPanel(stateWithUserAndMask(), localization(), clicked::add);

        dispatchPress(panel, 339, 280);
        dispatchDrag(panel, 380, 330);
        dispatchRelease(panel, 380, 330);
        dispatchClick(panel, 380, 330);
        assertTrue(clicked.isEmpty(), "drag must not trigger the node click");
        assertEquals(41, panel.offsetX());
        assertEquals(50, panel.offsetY());

        // 位移 ≤ 4px 仍视为点击：在已平移的视口里按下节点新屏幕位置、2px 内拖动后释放。
        clicked.clear();
        dispatchPress(panel, 380, 330);
        dispatchDrag(panel, 382, 332);
        dispatchRelease(panel, 382, 332);
        dispatchClick(panel, 382, 332);
        assertTrue(clicked.contains("user-1"), "small drag must still count as a node click");
    }

    @Test
    void doubleClickResetsViewTransform() {
        final GraphPanel panel = new GraphPanel(stateWithUserAndMask(), localization(), clicked -> { });
        panel.zoomAt(new Point(300, 300), 2.0);
        panel.panBy(40, 50);
        assertTrue(panel.scale() > 1);
        dispatchClick(panel, 200, 200, 2);
        assertEquals(1.0, panel.scale(), 1e-9);
        assertEquals(0, panel.offsetX());
        assertEquals(0, panel.offsetY());
    }

    @Test
    void setViewScaleClampsAndAnchorsAtViewportCenter() {
        final GraphPanel panel = new GraphPanel(stateWith(2), localization(), clicked -> { });
        panel.setSize(800, 600);

        panel.setViewScale(0.05);
        assertEquals(0.2, panel.scale(), 1e-9, "scale must clamp to MIN_SCALE");
        panel.setViewScale(10.0);
        assertEquals(4.0, panel.scale(), 1e-9, "scale must clamp to MAX_SCALE");

        // 中心锚点：缩放前后视口中心 (400,300) 的逻辑坐标不变。
        panel.setViewScale(1.0);
        panel.zoomAt(new Point(400, 300), 2.0);
        assertEquals(2.0, panel.scale(), 1e-9);
        final double logicalX = (400 - panel.offsetX()) / panel.scale();
        final double logicalY = (300 - panel.offsetY()) / panel.scale();
        panel.setViewScale(0.75);
        assertEquals(logicalX, (400 - panel.offsetX()) / panel.scale(), 1e-6);
        assertEquals(logicalY, (300 - panel.offsetY()) / panel.scale(), 1e-6);
    }

    @Test
    void viewScaleListenerFiresOnZoomResetAndSet() {
        final GraphPanel panel = new GraphPanel(stateWith(2), localization(), clicked -> { });
        final List<Double> seen = new ArrayList<>();
        panel.setViewScaleListener(seen::add);

        panel.zoomAt(new Point(100, 100), 1.2);
        panel.setViewScale(2.5);
        panel.resetView();
        assertEquals(3, seen.size());
        assertEquals(1.2, seen.get(0), 1e-9);
        assertEquals(2.5, seen.get(1), 1e-9);
        assertEquals(1.0, seen.get(2), 1e-9);

        panel.zoomAt(new Point(100, 100), 1 / 1.2);
        assertEquals(1 / 1.2, panel.scale(), 1e-9);
        assertEquals(4, seen.size());
        assertEquals(1 / 1.2, seen.get(3), 1e-9);

        // 空态（无节点）时 scale 变化仍同步 listener。
        final GraphPanel empty = new GraphPanel(new ClipMaskViewerState(), localization(), clicked -> { });
        final List<Double> emptySeen = new ArrayList<>();
        empty.setViewScaleListener(emptySeen::add);
        empty.setViewScale(3.0);
        assertEquals(List.of(3.0), emptySeen);

        // listener 替换/置 null 不抛异常。
        panel.setViewScaleListener(null);
        panel.setViewScale(3.0);
        panel.resetView();
        assertEquals(1.0, panel.scale(), 1e-9);
    }

    @Test
    void hitDetectionUsesTransformedCoordinates() {
        final List<String> clicked = new ArrayList<>();
        final GraphPanel panel = new GraphPanel(stateWithUserAndMask(), localization(), clicked::add);
        panel.zoomAt(new Point(200, 200), 2.0);
        assertEquals(-200, panel.offsetX());
        assertEquals(-200, panel.offsetY());
        // user-1 逻辑 (339,280) → 屏幕 (478,360)；圆心逻辑 (200,200) → 屏幕 (200,200)。
        assertNotNull(panel.findNode(new Point(478, 360)));
        assertNull(panel.findNode(new Point(200, 200)));
        dispatchClick(panel, 478, 360);
        assertTrue(clicked.contains("user-1"), "click on transformed node must reach the callback");

        panel.panBy(-50, 20);
        // mask-1 逻辑 (61,280) → 屏幕 (-128,380)。
        assertNotNull(panel.findNode(new Point(-128, 380)));
    }

    /** 统计 [startDeg, endDeg)（可跨 0°）扇区内各同心层（从内圈起）的节点数。 */
    private static List<Integer> ringSizes(final GraphPanel panel, final int startDeg, final int endDeg) {
        final int center = panel.getPreferredSize().width / 2;
        final int[] counts = new int[16];
        int maxRing = -1;
        for (Point point : panel.nodeCenters()) {
            double angle = Math.toDegrees(Math.atan2(point.y - center, point.x - center));
            if (angle < 0) {
                angle += 360;
            }
            final boolean inside = startDeg < endDeg
                ? angle >= startDeg && angle < endDeg
                : angle >= startDeg || angle < endDeg;
            if (!inside) {
                continue;
            }
            final double distance = Math.hypot(point.x - center, point.y - center);
            // 层半径 = MIN_RADIUS(160) + layer * NODE_SPACING(46)，坐标取整误差 < 2px。
            final int ring = (int) Math.round((distance - 160) / 46.0);
            counts[ring]++;
            maxRing = Math.max(maxRing, ring);
        }
        final List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i <= maxRing; i++) {
            sizes.add(counts[i]);
        }
        return sizes;
    }
    private static void dispatchClick(final JComponent panel, final int x, final int y) {
        dispatchClick(panel, x, y, 1);
    }

    private static void dispatchClick(
        final JComponent panel,
        final int x,
        final int y,
        final int clickCount
    ) {
        panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, x, y, clickCount, false, MouseEvent.BUTTON1));
    }

    private static void dispatchPress(final JComponent panel, final int x, final int y) {
        panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void dispatchDrag(final JComponent panel, final int x, final int y) {
        panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.BUTTON1));
    }

    private static void dispatchRelease(final JComponent panel, final int x, final int y) {
        panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static ClipMaskViewerState stateWithUserAndMask() {
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service(
            record("user-1", "A", false, "mask-1"),
            record("mask-1", "M", false)));
        return state;
    }
    private static ClipMaskViewerState stateWith(final int perGroup) {
        final List<ClipMaskRecord> records = new ArrayList<>();
        for (int i = 0; i < perGroup; i++) {
            // 纯蒙版：被引用、无蒙版。
            records.add(record("mask-" + i, "Mask " + i, false));
            // 既是也用：被引用、有蒙版。
            records.add(record("both-" + i, "Both " + i, false, "mask-" + i));
            // 纯使用者：有蒙版、不被引用。
            records.add(record("user-" + i, "User " + i, false, "mask-" + i));
        }
        final ClipMaskViewerState state = new ClipMaskViewerState();
        state.refreshData(service(records.toArray(new ClipMaskRecord[0])));
        return state;
    }

    private static CubismClipMaskService service(final ClipMaskRecord... records) {
        return () -> List.of(records);
    }

    private static ClipMaskRecord record(
        final String guid,
        final String id,
        final boolean inverted,
        final String... masks
    ) {
        return new ClipMaskRecord(guid, id, guid, inverted, List.of(masks));
    }

    private static PluginLocalization localization() {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.ENGLISH; }
            @Override public String text(final String key) { return key; }
            @Override public String format(final String key, final Object... arguments) { return key; }
            @Override public boolean contains(final String key) { return true; }
        };
    }
}
