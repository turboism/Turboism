package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JTable;

/**
 * Human-paced layer reorder in the native parts palette. Unlike the canvas
 * workloads this uses the real OS pointer (Robot), because Swing drag-and-drop
 * is driven by the platform drag gesture, not by synthetic AWT events.
 *
 * <p>The drag is paced like a person: press, a short hold, 16 ms pointer steps,
 * a short hold, release. The measured window is press-to-first-completed-display
 * and press-to-caught-up; row order and native Undo are separate correctness
 * gates.</p>
 */
final class PaletteReorderWorkload {
    private static final String PART_TABLE = "com.live2d.cubism.view.palette.parts.CPartsTreeTable.g";
    private static final int DRAG_STEPS = 12;
    private static final int STEP_MILLIS = 16;

    private final String fixture;
    private final Path state;
    private Frame window;
    private JComponent canvas;
    private JTable table;
    private NativeInteractionHost host;

    PaletteReorderWorkload(final String fixture, final Path state) {
        if (fixture == null || fixture.isBlank()) throw new IllegalArgumentException("fixture absent");
        this.fixture = fixture;
        this.state = state;
    }

    void run() throws Exception {
        awaitReady();
        if (!onEdt(table::isShowing)) {
            ensurePaletteVisible();
            onEdt(() -> {
                discover();
                return null;
            });
        }
        if (!onEdt(table::isShowing)) {
            throw new IllegalStateException("parts palette table is not showing after activation");
        }
        onEdt(() -> {
            table.requestFocusInWindow();
            return null;
        });
        awaitStableCanvas();
        onEdt(() -> {
            host = new NativeInteractionHost(fixture, window, canvas);
            return null;
        });
        final List<String> baselineOrder = onEdt(this::rowNames);
        if (baselineOrder.size() < 3) {
            throw new IllegalStateException("parts palette has fewer than 3 rows: " + baselineOrder);
        }
        final NativeInteractionHost.Geometry baselineGeometry = onEdt(host::geometry);
        final String baselineUndo = onEdt(host::undoState);
        final boolean baselineModified = onEdt(host::modified);
        final int baselineUndoPosition = onEdt(host::undoPosition);
        final StringBuilder report = new StringBuilder("schemaVersion=1\n")
            .append("workload=human-native-layer-reorder\n")
            .append("performanceAccepted=false\n")
            .append("latencyDefinition=pointer-press-to-first-completed-display\n")
            .append("inputDriver=os-robot\n")
            .append("fixture=").append(fixture).append('\n')
            .append("table=").append(table.getClass().getName()).append('\n')
            .append("tableRows=").append(baselineOrder.size()).append('\n')
            .append("orderBefore=").append(baselineOrder).append('\n')
            .append("undoBefore=").append(escape(baselineUndo)).append('\n')
            .append("modifiedBefore=").append(baselineModified).append('\n')
            .append("undoPositionBefore=").append(baselineUndoPosition).append('\n')
            .append("steps=").append(DRAG_STEPS).append('\n')
            .append("stepMillis=").append(STEP_MILLIS).append('\n');
        Files.writeString(state.resolve("reorder-benchmark.txt"), report.toString());
        try {
            Files.writeString(state.resolve("reorder-progress.txt"), "stage=warmup\n");
            reorderOnce(true);
            Thread.sleep(800L);
            onEdt(() -> { host.undo(); return null; });
            onEdt(() -> null);
            Thread.sleep(350L);
            final List<String> warmupOrder = onEdt(this::rowNames);
            if (!warmupOrder.equals(baselineOrder)) {
                throw new IllegalStateException("warm-up Undo did not restore the layer order: " + warmupOrder);
            }

            Files.writeString(state.resolve("reorder-progress.txt"), "stage=measuring\n");
            final HumanTrace.FrameTimeline frames = onEdt(() -> HumanTrace.FrameTimeline.attach(canvas, () -> ""));
            final HumanTrace.MouseObserver observer = onEdt(() -> HumanTrace.MouseObserver.attach(table));
            try {
                frames.clear();
                observer.clear();
                reorderOnce(false);
                awaitFrames(frames, 400L, 20_000L);
                final List<HumanTrace.FrameTimeline.Frame> frameList = frames.snapshot();
                final List<HumanTrace.MouseObserver.Moved> moved = observer.snapshot();
                final List<String> orderAfter = onEdt(this::rowNames);
                if (orderAfter.equals(baselineOrder)) {
                    throw new IllegalStateException("Robot reorder did not change the layer order: " + orderAfter);
                }
                final NativeInteractionHost.Geometry geometryAfter = onEdt(host::geometry);
                if (!NativeInteractionHost.changed(baselineGeometry, geometryAfter).isEmpty()) {
                    throw new IllegalStateException("layer reorder changed mesh geometry");
                }
                onEdt(() -> { host.undo(); return null; });
                onEdt(() -> null);
                Thread.sleep(350L);
                final List<String> undoOrder = onEdt(this::rowNames);
                final boolean undoRestoredOrder = undoOrder.equals(baselineOrder);
                onEdt(() -> { host.redo(); return null; });
                onEdt(() -> null);
                Thread.sleep(350L);
                final List<String> redoOrder = onEdt(this::rowNames);
                final boolean redoReplayed = redoOrder.equals(orderAfter);
                onEdt(() -> { host.undo(); return null; });
                onEdt(() -> null);
                Thread.sleep(350L);
                final List<String> finalOrder = onEdt(this::rowNames);
                if (!undoRestoredOrder || !redoReplayed || !finalOrder.equals(baselineOrder)) {
                    throw new IllegalStateException("layer reorder Undo/Redo failed: undo=" + undoOrder
                        + " redo=" + redoOrder + " final=" + finalOrder + " baseline=" + baselineOrder);
                }
                final Metrics metrics = measure(moved, frameList);
                report.append("orderAfter=").append(orderAfter).append('\n')
                    .append("undoRestoredOrder=true\n")
                    .append("redoReplayed=true\n")
                    .append("frames=").append(metrics.frames()).append('\n')
                    .append("pointerEvents=").append(metrics.pointerEvents()).append('\n')
                    .append("inputToFirstFrameNanos=").append(metrics.inputToFirstFrameNanos()).append('\n')
                    .append("settleNanos=").append(metrics.settleNanos()).append('\n')
                    .append("frameRate=").append(metrics.frameRate()).append('\n')
                    .append("frameIntervalP50Nanos=").append(metrics.frameIntervalP50()).append('\n')
                    .append("frameIntervalP95Nanos=").append(metrics.frameIntervalP95()).append('\n')
                    .append("frameIntervalP99Nanos=").append(metrics.frameIntervalP99()).append('\n')
                    .append("maxEventsPerFrame=").append(metrics.maxEventsPerFrame()).append('\n')
                    .append("geometryRestored=")
                    .append(NativeInteractionHost.changed(baselineGeometry, onEdt(host::geometry)).isEmpty())
                    .append('\n')
                    .append("undoPositionAfter=").append(onEdt(host::undoPosition)).append('\n')
                    .append("modifiedAfter=").append(onEdt(host::modified)).append('\n')
                    .append("status=PASS\n");
                Files.writeString(state.resolve("reorder-frames.tsv"), frameTimeline(frameList));
                Files.writeString(state.resolve("reorder-events.tsv"), pointerTimeline(moved));
                Files.writeString(state.resolve("reorder-benchmark.txt"), report.toString());
            } finally {
                onEdt(() -> {
                    frames.close();
                    observer.close();
                    return null;
                });
            }
            Files.writeString(state.resolve("reorder-progress.txt"), "stage=done\n");
        } catch (Throwable failure) {
            report.append("status=FAIL\nerror=").append(failure).append('\n');
            Files.writeString(state.resolve("reorder-benchmark.txt"), report.toString());
            throw failure;
        } finally {
            onEdt(() -> { host.restoreSelection(); return null; });
        }
    }

    /** One press-hold-drag-hold-release in the parts palette. */
    private void reorderOnce(final boolean warmup) throws Exception {
        final int fromRow = 1;
        final int toRow = 3;
        if (!onEdt(table::isShowing)) {
            ensurePaletteVisible();
            onEdt(() -> { table.requestFocusInWindow(); return null; });
            Thread.sleep(400L);
        }
        if (!onEdt(table::isShowing)) {
            throw new IllegalStateException("parts palette table is not showing before the drag");
        }
        final Rectangle from = onEdt(() -> table.getCellRect(fromRow, 2, true));
        final Rectangle to = onEdt(() -> table.getCellRect(toRow, 2, true));
        final Point origin = onEdt(table::getLocationOnScreen);
        if (from.width <= 0 || to.width <= 0) {
            throw new IllegalStateException("parts palette row bounds absent: " + from + " " + to);
        }
        final Point start = new Point(origin.x + from.x + from.width / 2,
            origin.y + from.y + from.height / 2);
        final Point end = new Point(origin.x + to.x + to.width / 2,
            origin.y + to.y + to.height / 2);
        final Robot robot = new Robot();
        robot.setAutoDelay(0);
        robot.mouseMove(start.x, start.y);
        robot.delay(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(120);
        for (int step = 1; step <= DRAG_STEPS; step++) {
            final double t = (double) step / DRAG_STEPS;
            final int x = (int) Math.round(start.x + (end.x - start.x) * t);
            final int y = (int) Math.round(start.y + (end.y - start.y) * t);
            robot.mouseMove(x, y);
            robot.delay(STEP_MILLIS);
        }
        robot.delay(120);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(120);
        if (!warmup) {
            Files.writeString(state.resolve("reorder-gesture.txt"),
                "fromRow=" + fromRow + "\ntoRow=" + toRow + "\nstart=" + start + "\nend=" + end + "\n");
        }
    }

    /** Opens the Parts palette through its native menu item when the dock is closed. */
    private void ensurePaletteVisible() throws Exception {
        onEdt(() -> {
            final Object app = Class.forName("com.live2d.cubism.CEAppCtrl", false,
                canvas.getClass().getClassLoader()).getMethod("access$get_instance$cp").invoke(null);
            final Object main = app.getClass().getMethod("getMainFrameCtrl").invoke(app);
            final Object parts = main.getClass().getMethod("getPaletteCtrl_parts").invoke(main);
            final Object paletteId = parts.getClass().getMethod("a").invoke(parts);
            final Object dock = main.getClass().getMethod("getDockManager").invoke(main);
            final Object manager = dock.getClass().getMethod("getPaletteManager").invoke(dock);
            final Object palette = manager.getClass().getMethod("getPalette", paletteId.getClass())
                .invoke(manager, paletteId);
            // The host may open in the Canvas Only workspace, where the Parts
            // palette exists but is not docked. Switch to the model workspace
            // before activating the palette.
            final Object modelWorkspace = dock.getClass().getMethod("getWorkspaceForModel").invoke(dock);
            if (modelWorkspace != null) {
                final Object modelGuid = modelWorkspace.getClass().getMethod("getGuid").invoke(modelWorkspace);
                manager.getClass().getMethod("setWorkspace", modelGuid.getClass())
                    .invoke(manager, modelGuid);
            }
            final Object workspace = manager.getClass().getMethod("getCurrentWorkspace").invoke(manager);
            final Object activated = workspace.getClass().getMethod("activate", palette.getClass())
                .invoke(workspace, palette);
            if (!Boolean.TRUE.equals(activated)) {
                final java.util.Map<?, ?> menus = (java.util.Map<?, ?>) main.getClass()
                    .getMethod("getPaletteMenuMap").invoke(main);
                final Object item = menus.get(paletteId);
                if (item == null) throw new IllegalStateException("parts palette menu item absent");
                final Object check = item.getClass().getMethod("getJCheckMenuItem").invoke(item);
                if (check instanceof javax.swing.JCheckBoxMenuItem menu && !menu.isSelected()) {
                    menu.doClick();
                }
            }
            final StringBuilder ui = new StringBuilder("paletteActivated=").append(activated)
                .append('\n').append("tableShowing=").append(table.isShowing())
                .append('\n').append("tableVisible=").append(table.isVisible())
                .append('\n').append("windowShowing=").append(window.isShowing())
                .append('\n');
            Component parent = table;
            for (int depth = 0; parent != null && depth < 24; depth++) {
                ui.append("ancestor=").append(parent.getClass().getName())
                    .append(" showing=").append(parent.isShowing())
                    .append(" visible=").append(parent.isVisible())
                    .append(" bounds=").append(parent.getBounds()).append('\n');
                parent = parent.getParent();
            }
            Files.writeString(state.resolve("ui-palette-before.txt"), ui.toString());
            return null;
        });
        Thread.sleep(900L);
        try {
            Files.writeString(state.resolve("ui-palette-after.txt"),
                "tableShowing=" + onEdt(table::isShowing) + "\n");
        } catch (Throwable failure) {
            Files.writeString(state.resolve("ui-palette-after.txt"),
                "failure=" + failure + "\n");
        }
    }

    /** The palette focus can settle the main canvas by one pixel; bind after it. */
    private void awaitStableCanvas() throws Exception {
        int lastWidth = -1;
        int lastHeight = -1;
        for (int attempt = 0; attempt < 40; attempt++) {
            final Point ignored = onEdt(() -> new Point(canvas.getWidth(), canvas.getHeight()));
            if (ignored.x == lastWidth && ignored.y == lastHeight && ignored.x > 0) return;
            lastWidth = ignored.x;
            lastHeight = ignored.y;
            Thread.sleep(100L);
        }
        throw new IllegalStateException("canvas size did not settle: " + lastWidth + "x" + lastHeight);
    }

    private void ensureWindowFocus() throws Exception {
        onEdt(() -> {
            window.toFront();
            window.requestFocus();
            return null;
        });
        for (int attempt = 0; attempt < 60; attempt++) {
            if (onEdt(() -> window.isFocused() && window.isActive())) return;
            Thread.sleep(50L);
        }
        throw new IllegalStateException("parts palette window did not become active");
    }

    private Metrics measure(final List<HumanTrace.MouseObserver.Moved> moved,
                            final List<HumanTrace.FrameTimeline.Frame> frames) {
        if (frames.isEmpty()) throw new IllegalStateException("layer reorder produced no completed display");
        if (moved.isEmpty()) throw new IllegalStateException("layer reorder produced no pointer event");
        final long firstPost = moved.get(0).nanoTime();
        final long lastPost = moved.get(moved.size() - 1).nanoTime();
        HumanTrace.FrameTimeline.Frame firstFrame = null;
        for (HumanTrace.FrameTimeline.Frame frame : frames) {
            if (frame.nanoTime() >= firstPost) {
                firstFrame = frame;
                break;
            }
        }
        if (firstFrame == null) throw new IllegalStateException("no display after pointer press");
        HumanTrace.FrameTimeline.Frame settleFrame = frames.get(frames.size() - 1);
        for (HumanTrace.FrameTimeline.Frame frame : frames) {
            if (frame.nanoTime() >= lastPost) settleFrame = frame;
        }
        final List<HumanTrace.FrameTimeline.Frame> window = new ArrayList<>();
        for (HumanTrace.FrameTimeline.Frame frame : frames) {
            if (frame.nanoTime() >= firstFrame.nanoTime() && frame.nanoTime() <= settleFrame.nanoTime()) {
                window.add(frame);
            }
        }
        final long[] intervals = new long[Math.max(0, window.size() - 1)];
        for (int i = 1; i < window.size(); i++) {
            intervals[i - 1] = Math.max(0L, window.get(i).nanoTime() - window.get(i - 1).nanoTime());
        }
        final long[] sorted = intervals.clone();
        Arrays.sort(sorted);
        final int[] eventsPerFrame = new int[window.size()];
        for (int i = 1; i < window.size(); i++) {
            int count = 0;
            for (HumanTrace.MouseObserver.Moved entry : moved) {
                if (entry.nanoTime() > window.get(i - 1).nanoTime()
                    && entry.nanoTime() <= window.get(i).nanoTime()) count++;
            }
            eventsPerFrame[i] = count;
        }
        int maxEvents = 0;
        for (int count : eventsPerFrame) maxEvents = Math.max(maxEvents, count);
        final double frameSeconds = Math.max(1e-9d,
            (settleFrame.nanoTime() - firstFrame.nanoTime()) / 1_000_000_000.0d);
        return new Metrics(window.size(), moved.size(),
            Math.max(0L, firstFrame.nanoTime() - firstPost),
            Math.max(0L, settleFrame.nanoTime() - lastPost),
            window.size() / frameSeconds,
            sorted.length == 0 ? 0L : percentile(sorted, 0.50d),
            sorted.length == 0 ? 0L : percentile(sorted, 0.95d),
            sorted.length == 0 ? 0L : percentile(sorted, 0.99d),
            maxEvents);
    }

    private static long percentile(final long[] sorted, final double fraction) {
        if (sorted.length == 0) return 0L;
        return sorted[Math.max(0, Math.min(sorted.length - 1,
            (int) Math.ceil(sorted.length * fraction) - 1))];
    }

    private record Metrics(int frames, int pointerEvents, long inputToFirstFrameNanos,
                           long settleNanos, double frameRate, long frameIntervalP50,
                           long frameIntervalP95, long frameIntervalP99, int maxEventsPerFrame) { }

    private void awaitFrames(final HumanTrace.FrameTimeline frames,
                             final long quietMillis, final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        long lastCount = -1L;
        long lastChange = System.nanoTime();
        while (System.nanoTime() < deadline) {
            final long count = frames.count();
            if (count != lastCount) {
                lastCount = count;
                lastChange = System.nanoTime();
            }
            if (count > 0L && System.nanoTime() - lastChange >= quietMillis * 1_000_000L) return;
            Thread.sleep(10L);
        }
        throw new IllegalStateException("layer reorder did not quiesce: frames=" + frames.count());
    }

    private List<String> rowNames() {
        final List<String> names = new ArrayList<>();
        for (int row = 0; row < table.getRowCount(); row++) {
            names.add(String.valueOf(table.getValueAt(row, 2)));
        }
        return List.copyOf(names);
    }

    private static String frameTimeline(final List<HumanTrace.FrameTimeline.Frame> frames) {
        final StringBuilder text = new StringBuilder("index\tnanoTime\tzoom\n");
        for (int i = 0; i < frames.size(); i++) {
            text.append(i).append('\t').append(frames.get(i).nanoTime()).append('\t')
                .append(frames.get(i).zoom()).append('\n');
        }
        return text.toString();
    }

    private static String pointerTimeline(final List<HumanTrace.MouseObserver.Moved> moved) {
        final StringBuilder text = new StringBuilder("index\tnanoTime\tid\tx\ty\tmodifiers\n");
        for (int i = 0; i < moved.size(); i++) {
            final HumanTrace.MouseObserver.Moved entry = moved.get(i);
            text.append(i).append('\t').append(entry.nanoTime()).append('\t')
                .append(entry.id()).append('\t').append(entry.x()).append('\t')
                .append(entry.y()).append('\t').append(entry.modifiers()).append('\n');
        }
        return text.toString();
    }

    private void awaitReady() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120L);
        while (System.nanoTime() < deadline) {
            try {
                onEdt(() -> {
                    discover();
                    return null;
                });
                return;
            } catch (Throwable notReady) {
                Thread.sleep(250L);
            }
        }
        throw new IllegalStateException("parts palette was not ready within 120 s");
    }

    private void discover() {
        final List<Frame> windows = new ArrayList<>();
        for (Window candidate : Window.getWindows()) {
            if (candidate instanceof Frame frame && frame.isShowing()
                && frame.getTitle().contains(fixture)) windows.add(frame);
        }
        if (windows.size() != 1) throw new IllegalStateException("fixture window count=" + windows.size());
        window = windows.get(0);
        final List<JComponent> canvases = new ArrayList<>();
        final List<JTable> tables = new ArrayList<>();
        collect(window, canvases, tables, 0);
        if (canvases.size() != 1) throw new IllegalStateException("unique GLJPanel not found: " + canvases.size());
        canvas = canvases.get(0);
        for (JTable candidate : tables) {
            if (candidate.getClass().getName().equals(PART_TABLE) && candidate.getRowCount() >= 3) {
                table = candidate;
                break;
            }
        }
        if (table == null) throw new IllegalStateException("parts palette table not found");
    }

    private static void collect(final Component component, final List<JComponent> canvases,
                                final List<JTable> tables, final int depth) {
        if (component == null || depth > 40) return;
        if (component instanceof JComponent jcomponent
            && component.getClass().getName().equals("com.jogamp.opengl.awt.GLJPanel")) {
            canvases.add(jcomponent);
        }
        if (component instanceof JTable jtable) tables.add(jtable);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, canvases, tables, depth + 1);
        }
    }

    private static String escape(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static <T> T onEdt(final Callable<T> action) throws Exception {
        final FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        return task.get(15L, TimeUnit.SECONDS);
    }
}
