package dev.turboism.validation.modelviewer;

import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.plugin.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Test-only UI automation of the installed production plugin and real SDK file chooser. */
public final class ViewerHostProbe implements TurboismPlugin {
    private PluginContext context;
    private volatile Thread task;
    private volatile Window viewer;
    private record Snapshot(boolean visible, long hash, int pixels) {}
    @Override public void init(PluginContext value) { context = value; }
    @Override public void enable() {
        task = new Thread(this::verify, "viewer-host-probe"); task.setDaemon(true); task.start();
    }
    private void verify() {
        Properties report = new Properties();
        report.setProperty("schemaVersion", "1");
        report.setProperty("runId", System.getProperty("turboism.validation.runId", "unknown"));
        report.setProperty("native-document.write", "not-applicable: plugin-owned viewer");
        report.setProperty("undo-redo-persistence", "not-applicable: no native document mutation");
        try {
            Path input = Path.of(System.getProperty("turboism.viewer.fixture"));
            report.setProperty("source.bytes", "" + Files.size(input));
            report.setProperty("source.sha256", HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
            var catalog = context.services().require(ActionCatalogService.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (catalog.actions().stream().noneMatch(a -> a.pluginId().equals("dev.turboism.plugin.model-import-3d")
                    && a.actionId().equals("model-import-3d.view"))) {
                if (System.nanoTime() > deadline) throw new AssertionError("Production viewer action missing");
                Thread.sleep(100);
            }
            catalog.invoke("dev.turboism.plugin.model-import-3d", "model-import-3d.view");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (viewer == null) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window candidate : Window.getWindows())
                        if ("turboism-model-viewer".equals(candidate.getName()) && candidate.isShowing()) viewer = candidate;
                });
                if (System.nanoTime() > deadline) throw new AssertionError("Production viewer did not open");
                Thread.sleep(50);
            }
            report.setProperty("entrypoint", "production action via ActionCatalogService");
            SwingUtilities.invokeAndWait(() -> ((JButton) component("model-viewer-open")).doClick());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            AtomicReference<JFileChooser> chooser = new AtomicReference<>();
            while (chooser.get() == null) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window candidate : Window.getWindows()) {
                        if (candidate.isShowing()) {
                            JFileChooser found = findChooser(candidate);
                            if (found != null) chooser.set(found);
                        }
                    }
                });
                if (System.nanoTime() > deadline) throw new AssertionError("SDK file chooser did not open");
                Thread.sleep(50);
            }
            SwingUtilities.invokeAndWait(() -> {
                chooser.get().setSelectedFile(input.toFile()); chooser.get().approveSelection();
            });
            report.setProperty("fileAccess", "real SDK chooser grant and bounded runtime read");
            var initial = awaitImage(null);
            report.setProperty("initial.visible", "" + initial.visible());
            if (!initial.visible() || initial.pixels() < 1000) throw new AssertionError("Blank production frame");
            SwingUtilities.invokeAndWait(() -> {
                Component canvas = component("model-viewer-canvas");
                long time = System.currentTimeMillis();
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, time, 0, 100, 100, 1, false));
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, time + 1,
                        InputEvent.BUTTON1_DOWN_MASK, 257, 115, 0, false));
            });
            var rotated = awaitImage(initial.hash());
            SwingUtilities.invokeAndWait(() -> {
                Component canvas = component("model-viewer-canvas");
                canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), 0, 100, 100, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -3));
            });
            var zoomed = awaitImage(rotated.hash());
            SwingUtilities.invokeAndWait(() -> ((JButton) component("model-viewer-reset")).doClick());
            var reset = awaitImage(zoomed.hash());
            if (reset.hash() != initial.hash()) throw new AssertionError("Reset did not restore frame");
            SwingUtilities.invokeAndWait(() -> ((JCheckBox) component("model-viewer-geometry")).doClick());
            var geometry = awaitImage(reset.hash());
            report.setProperty("initial.hash", "" + initial.hash());
            report.setProperty("rotated.hash", "" + rotated.hash());
            report.setProperty("zoom.hash", "" + zoomed.hash());
            report.setProperty("reset.hash", "" + reset.hash());
            report.setProperty("geometry.hash", "" + geometry.hash());
            SwingUtilities.invokeAndWait(this::closeViewer);
            for (int i = 0; i < 100 && viewerWorkers() != 0; i++) Thread.sleep(50);
            if (viewerWorkers() != 0) throw new AssertionError("Viewer worker survived close");
            if (viewer.isShowing()) throw new AssertionError("Viewer window survived close");
            report.setProperty("cleanup.window", "closed"); report.setProperty("cleanup.worker", "terminated");
            report.setProperty("status", "PASS");
        } catch (Throwable failure) {
            report.setProperty("status", "FAIL"); report.setProperty("failure", failure.toString());
            context.logger().error("Viewer host probe failed", failure);
        } finally {
            try { SwingUtilities.invokeAndWait(this::closeViewer); } catch (Exception ignored) {}
            try {
                Path state = context.paths().stateDir(); Files.createDirectories(state);
                try (var output = Files.newOutputStream(state.resolve("result.properties"))) {
                    report.store(output, "Production viewer host verification");
                }
                String runId = System.getProperty("turboism.validation.runId", "");
                if (Boolean.getBoolean("turboism.viewer.exitAfterProbe") && runId.matches("queue-[a-f0-9]{32}")
                        && state.toString().contains(runId)) {
                    Thread.sleep(2000); System.exit(0);
                }
            } catch (Exception failure) { context.logger().error("Viewer report failed", failure); }
        }
    }
    private void closeViewer() {
        if (viewer != null && viewer.isDisplayable()) viewer.dispatchEvent(new WindowEvent(viewer, WindowEvent.WINDOW_CLOSING));
    }
    private static long viewerWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("turboism-model-viewer")).count();
    }
    private Component component(String name) {
        Component found = find(viewer, name);
        if (found == null) throw new AssertionError("Viewer control missing: " + name);
        return found;
    }
    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            Component found = find(child, name); if (found != null) return found;
        }
        return null;
    }
    private static JFileChooser findChooser(Component root) {
        if (root instanceof JFileChooser chooser) return chooser;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            JFileChooser found = findChooser(child); if (found != null) return found;
        }
        return null;
    }
    private Snapshot snapshot() throws Exception {
        AtomicReference<Snapshot> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Component canvas = component("model-viewer-canvas");
                // Test-only reflection reads our plugin's rendered image, never a Cubism host object.
                var field = canvas.getClass().getDeclaredField("image"); field.setAccessible(true);
                BufferedImage image = (BufferedImage) field.get(canvas);
                if (image == null) { value.set(new Snapshot(viewer.isShowing(), 0, 0)); return; }
                long hash = 1; int pixels = 0, background = image.getRGB(0, 0);
                for (int y = 0; y < image.getHeight(); y += 4) for (int x = 0; x < image.getWidth(); x += 4) {
                    int color = image.getRGB(x, y); hash = 31 * hash + color; if (color != background) ++pixels;
                }
                value.set(new Snapshot(viewer.isShowing(), hash, pixels * 16));
            } catch (Exception failure) { error.set(failure); }
        });
        if (error.get() != null) throw error.get(); return value.get();
    }
    private Snapshot awaitImage(Long previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            var value = snapshot();
            if (value.pixels() > 0 && (previous == null || value.hash() != previous)) return value;
            Thread.sleep(100);
        }
        throw new AssertionError("Production viewer frame timed out");
    }
    @Override public void disable() { SwingUtilities.invokeLater(this::closeViewer); if (task != null) task.interrupt(); }
}
