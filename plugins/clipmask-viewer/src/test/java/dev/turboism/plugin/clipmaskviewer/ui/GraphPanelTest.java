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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphPanelTest {

    private static final int LEGACY_ROW_WIDTH_ESTIMATE = 80 + 60 * 80;

    @Test
    void circularLayoutKeepsSquareAspectAndAvoidsRowOverflow() {
        final ClipMaskViewerState state = stateWith(20); // 60 节点（三组各 20）
        final GraphPanel panel = new GraphPanel(state, localization(), clicked -> { });

        final Dimension size = panel.getPreferredSize();
        // 圆形布局：长宽比 1:1；同 60 节点下行布局需 ~4880px 宽，圆形显著收窄。
        assertTrue(Math.abs((double) size.width / size.height - 1.0) < 0.01,
            "expected square aspect, got " + size);
        assertTrue(size.width < LEGACY_ROW_WIDTH_ESTIMATE / 2,
            "circular width " + size.width + " must be well below row-layout width "
                + LEGACY_ROW_WIDTH_ESTIMATE);
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

    private static void dispatchClick(final JComponent panel, final int x, final int y) {
        panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
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
