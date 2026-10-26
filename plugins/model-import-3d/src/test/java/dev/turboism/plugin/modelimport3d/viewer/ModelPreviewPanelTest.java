package dev.turboism.plugin.modelimport3d.viewer;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ModelPreviewPanelTest {
    @Test
    void rendersTheImportedSceneAndSupportsDragZoomResetAndClose() throws Exception {
        ViewerScene scene;
        try (var input = getClass().getResourceAsStream("/META-INF/turboism/model-import-3d/presets/cube.glb")) {
            assertNotNull(input);
            scene = GlbViewerLoader.load(input.readAllBytes(), () -> false);
        }
        var failure = new AtomicReference<Throwable>();
        var holder = new AtomicReference<ModelPreviewPanel>();
        SwingUtilities.invokeAndWait(() -> holder.set(new ModelPreviewPanel(scene, key -> key, failure::set)));
        var panel = holder.get();
        var canvas = (JPanel) find(panel, "model-import-preview-canvas");
        try {
            int[] initial = awaitPixels(
                    canvas,
                    pixels -> Arrays.stream(pixels)
                            .anyMatch(value -> value != SoftwareModelRenderer.DEFAULT_BACKGROUND_COLOR));
            SwingUtilities.invokeAndWait(() -> {
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0, 0, 100, 100, 1, false));
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0, 0, 140, 120, 0, false));
            });
            int[] rotated = awaitPixels(canvas, pixels -> !Arrays.equals(initial, pixels));
            for (int x = 0; x < 256; x++) {
                assertEquals(SoftwareModelRenderer.DEFAULT_BACKGROUND_COLOR, rotated[x]);
                assertEquals(SoftwareModelRenderer.DEFAULT_BACKGROUND_COLOR, rotated[239 * 256 + x]);
            }
            SwingUtilities.invokeAndWait(() -> canvas.dispatchEvent(new MouseWheelEvent(
                    canvas,
                    MouseEvent.MOUSE_WHEEL,
                    0,
                    0,
                    100,
                    100,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    -2)));
            awaitPixels(canvas, pixels -> !Arrays.equals(rotated, pixels));
            SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "model-import-preview-reset")).doClick());
            awaitPixels(canvas, pixels -> Arrays.equals(initial, pixels));
            assertNull(failure.get());
        } finally {
            SwingUtilities.invokeAndWait(panel::close);
        }
        awaitPixels(
                canvas,
                pixels -> Arrays.stream(pixels)
                        .allMatch(value -> value == SoftwareModelRenderer.DEFAULT_BACKGROUND_COLOR));
    }

    private static int[] awaitPixels(JPanel canvas, Predicate<int[]> condition) {
        var result = new AtomicReference<int[]>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                SwingUtilities.invokeAndWait(() -> {
                    canvas.setSize(256, 240);
                    var image = new BufferedImage(256, 240, BufferedImage.TYPE_INT_ARGB);
                    var graphics = image.createGraphics();
                    try {
                        canvas.paint(graphics);
                    } finally {
                        graphics.dispose();
                    }
                    result.set(image.getRGB(0, 0, 256, 240, null, 0, 256));
                });
                if (condition.test(result.get())) return;
                Thread.sleep(10);
            }
        });
        return result.get();
    }

    private static Component find(Container parent, String name) {
        for (var component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container container) {
                Component match = find(container, name);
                if (match != null) return match;
            }
        }
        return null;
    }
}
