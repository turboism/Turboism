package dev.turboism.ui.resource;

import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.ui.panel.SwingPanelViewRenderer;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parent-owned, headless service/renderer seam test. Synthetic pixels only; no native assets or host. */
class NativeIconInlineLabelIntegrationTest {
    private static final int LIGHT = 0xffc12345;
    private static final int DARK = 0xff2345c1;
    private static final int DISABLED = 0xff23c145;

    @Test
    void presentationRefreshAndDisposalKeepTheSameInteractiveRow() throws Exception {
        try (var resources = resources()) {
            final var reference = resources.sdkView().cubismIcon(CubismIcon.ART_MESH);
            final var label = label(resources);
            final List<String> actions = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                final JCheckBox row = (JCheckBox) SwingPanelViewRenderer.render(
                    PanelView.toggle("entry-stable-42", label, true, "jump-stable-42"),
                    (id, event) -> actions.add(id), Locale.CHINA, resources::resolve
                );
                assertEquals("移动 图形网格 Artmesh 左眼皮 <测试>", row.getAccessibleContext().getAccessibleName());
                final BufferedImage initial = paint(row);
                final int iconWidth = initial.getWidth();
                assertTrue(pixels(initial, LIGHT) > 0);
                row.doClick(0);

                resources.updatePresentation(NativeIconVariant.Theme.DARK, 200);
                SwingPanelViewRenderer.refreshInlineLabelPresentation(row);
                final BufferedImage updated = paint(row);
                assertEquals(0, pixels(updated, LIGHT));
                assertTrue(pixels(updated, DARK) > 0);
                assertEquals(iconWidth, updated.getWidth(), "DPI changes retain logical icon dimensions");
                assertEquals(reference, resources.sdkView().cubismIcon(CubismIcon.ART_MESH));
                row.doClick(0);

                resources.close();
                SwingPanelViewRenderer.refreshInlineLabelPresentation(row);
                final BufferedImage unavailable = paint(row);
                assertEquals(0, pixels(unavailable, DARK));
                assertTrue(unavailable.getWidth() > iconWidth, "localized type text replaces the icon slot");
                assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, resources.sdkView().availability(reference));
                assertTrue(row.isEnabled());
                assertEquals("entry-stable-42", row.getName());
                row.doClick(0);
                assertEquals(List.of("jump-stable-42", "jump-stable-42", "jump-stable-42"), actions);
            });
        }
    }

    @Test
    void refreshReachesGrayEnabledRowsInsideScrollContainers() throws Exception {
        try (var resources = resources()) {
            resources.updatePresentation(NativeIconVariant.Theme.DARK, 200);
            final List<String> actions = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                final JComponent root = SwingPanelViewRenderer.render(
                    PanelView.scroll(PanelView.column(
                        PanelView.toggle("redo-stable-9", label(resources), false, true, "jump-stable-9")
                    )), (id, event) -> actions.add(id), Locale.CHINA, resources::resolve
                );
                final JCheckBox row = findRow(root);
                assertNotNull(row);
                final BufferedImage before = paint(row);
                assertTrue(pixels(before, DISABLED) > 0, "gray requests disabled icon, not disabled navigation");
                assertTrue(row.isEnabled());
                resources.close();
                SwingPanelViewRenderer.refreshInlineLabelPresentation(root);
                final BufferedImage after = paint(row);
                assertEquals(0, pixels(after, DISABLED));
                assertTrue(after.getWidth() > before.getWidth());
                row.doClick(0);
                assertEquals(List.of("jump-stable-9"), actions);
                assertTrue(row.isSelected());
            });
        }
    }

    private static UiInlineLabel label(final RuntimeUiResourceService resources) {
        return UiInlineLabel.of(
            UiInlineLabel.textRun("移动 "),
            UiInlineLabel.iconRun(resources.sdkView().cubismIcon(CubismIcon.ART_MESH), "图形网格 Artmesh"),
            UiInlineLabel.textRun(" 左眼皮 <测试>")
        );
    }

    private static RuntimeUiResourceService resources() {
        return new RuntimeUiResourceService(new CubismNativeIconResolver(Map.of(
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.LIGHT, 100, false), image(16, LIGHT),
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.DARK, 200, false), image(32, DARK),
            new NativeIconVariant(CubismIcon.ART_MESH, NativeIconVariant.Theme.DARK, 200, true), image(32, DISABLED)
        ), UiIconAvailability.RESOURCE_UNAVAILABLE));
    }

    private static BufferedImage image(final int size, final int color) {
        final BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) result.setRGB(x, y, color);
        return result;
    }

    private static BufferedImage paint(final JComponent component) {
        component.setSize(0, 0);
        final Dimension size = component.getPreferredSize();
        component.setSize(size);
        final BufferedImage canvas = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        final var graphics = canvas.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

    private static long pixels(final BufferedImage image, final int color) {
        long count = 0;
        for (int x = 0; x < image.getWidth(); x++) for (int y = 0; y < image.getHeight(); y++) {
            if (image.getRGB(x, y) == color) count++;
        }
        return count;
    }

    private static JCheckBox findRow(final Component component) {
        if (component instanceof JCheckBox row) return row;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            final JCheckBox row = findRow(child);
            if (row != null) return row;
        }
        return null;
    }
}
