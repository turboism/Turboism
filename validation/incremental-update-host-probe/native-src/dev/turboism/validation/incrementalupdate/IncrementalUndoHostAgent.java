package dev.turboism.validation.incrementalupdate;

import com.live2d.cubism.doc.model.CModel;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformer;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.graphics3d.sceneGraph.GCameraManager;
import com.live2d.graphics3d.type.GVector2;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Task-only native mouse/Undo/Redo validation; never forces model rebuilds at frame barriers. */
public final class IncrementalUndoHostAgent {
    private static final String ENABLE = "turboism.optimization.incrementalUpdate";
    private static final Properties RESULT = new Properties();
    private static Object app, view;
    private static CModelingDocument doc;
    private static CModel model;
    private static Component canvas;
    private static CWarpDeformerSource warp;
    private static Path output;

    public static void premain(String ignored, Instrumentation instrumentation) {
        Path home = Path.of(System.getProperty("turboism.home", ""));
        String runId = System.getProperty("turboism.validation.runId", "");
        if (!runId.matches("queue-[0-9a-f]{32}") || home.getParent() == null
                || !runId.equals(home.getParent().getFileName().toString())
                || !"turboism-home".equals(home.getFileName().toString())) {
            throw new IllegalArgumentException("native undo validation requires a bound queue task home");
        }
        Thread worker = new Thread(() -> {
            try {
                output = Path.of(System.getProperty("turboism.home")).resolve("state/incremental-undo");
                Files.createDirectories(output);
                awaitContext();
                require(System.getProperties().get("turboism.incremental-update.reinit") instanceof java.util.function.Consumer<?>, "reinit callback absent");
                RESULT.setProperty("version", System.getProperty("turboism.validation.hostVersion", "unknown"));
                RESULT.setProperty("mirrors", "disabled hooks and no mirror plugins staged");
                RESULT.setProperty("frameBarrier", "repaintCanvas only; no source rebuild or updateParamInstance");
                prepare();
                Map<String, int[]>[] nativeLeg = runLeg(false);
                Map<String, int[]>[] incrementalLeg = runLeg(true);
                if (Boolean.getBoolean("turboism.validation.incrementalUndo.repeatNative")) {
                    Map<String, int[]>[] repeated = runLeg(false, "native-repeat");
                    for (int i = 0; i < repeated.length; i++) same(nativeLeg[i], repeated[i], "repeated native geometry phase " + i);
                    for (String phase : List.of("before", "dragged", "undo", "redo")) {
                        RESULT.setProperty("native-repeat." + phase + ".matchesInitialPixels", Boolean.toString(
                                RESULT.getProperty("off-" + phase + ".pixelSha256").equals(
                                        RESULT.getProperty("native-repeat-" + phase + ".pixelSha256"))));
                    }
                }
                for (int i = 0; i < nativeLeg.length; i++) same(nativeLeg[i], incrementalLeg[i], "native/incremental phase " + i);
                for (String phase : List.of("before", "dragged", "undo", "redo")) {
                    require(RESULT.getProperty("off-" + phase + ".pixelSha256").equals(
                            RESULT.getProperty("on-" + phase + ".pixelSha256")), "native/incremental pixels differ: " + phase);
                }
                RESULT.setProperty("differential", "all source and calculated coordinate raw bits match in all phases");
                RESULT.setProperty("status", "PASS");
            } catch (Throwable failure) {
                failure.printStackTrace();
                RESULT.setProperty("status", "FAIL");
                RESULT.setProperty("failure", failure.toString());
            } finally {
                try {
                    if (output != null) {
                        try (var stream = Files.newOutputStream(output.resolve("result.txt"))) {
                            RESULT.store(stream, "Native Editor Warp drag / Undo / Redo validation");
                        }
                    }
                } catch (Exception failure) { failure.printStackTrace(); }
                System.out.println("INCREMENTAL_UNDO_RESULT " + RESULT);
                // The carrier only admits a bound isolated task, never the user's editor session.
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                Runtime.getRuntime().exit("PASS".equals(RESULT.getProperty("status")) ? 0 : 2);
            }
        }, "incremental-undo-native-validation");
        worker.setDaemon(true);
        worker.start();
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static Object call(Object receiver, String name, Object... args) throws Exception {
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean fits = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = method.getParameterTypes()[i];
                if (args[i] != null && !type.isInstance(args[i])
                        && !(type == boolean.class && args[i] instanceof Boolean)) fits = false;
            }
            if (fits) return method.invoke(receiver, args);
        }
        throw new NoSuchMethodException(receiver.getClass() + "." + name);
    }

    private static void awaitContext() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        int attempts = 0;
        while (System.nanoTime() < deadline) {
            try {
                boolean ready = edt(() -> {
                    serviceStartup();
                    app = Class.forName("com.live2d.cubism.CEAppCtrl").getMethod("access$get_instance$cp").invoke(null);
                    if (app == null) return false;
                    view = call(app, "getCurrentViewContext");
                    RESULT.setProperty("context.view", view == null ? "null" : view.getClass().getName());
                    if (view == null || !(call(view, "getDoc") instanceof CModelingDocument)) return false;
                    doc = (CModelingDocument) call(view, "getDoc");
                    model = (CModel) call(view, "getModel");
                    List<Component> canvases = new ArrayList<>();
                    // Old hosts also show a texture-preview GL panel. Bind to this modeling view.
                    walk((Component) call(call(view, "getComponent"), "getJComponent"), canvases);
                    RESULT.setProperty("context.canvases", Integer.toString(canvases.size()));
                    if (canvases.size() != 1) return false;
                    canvas = canvases.get(0);
                    return canvas.getWidth() > 100 && canvas.getHeight() > 100;
                });
                if (ready) { Thread.sleep(2000); return; }
            } catch (Exception notReady) {
                RESULT.setProperty("context.lastError", notReady.toString());
            }
            if (++attempts % 20 == 0) System.out.println("INCREMENTAL_UNDO_WAIT " + RESULT);
            Thread.sleep(300);
        }
        throw new IllegalStateException("modeling context not ready");
    }

    private static void serviceStartup() {
        String action = dev.turboism.validation.NativeFreeStartup.selectFreeOnly();
        if (action != null) RESULT.setProperty("startup.lastAction", action);
    }

    private static void walk(Component c, List<Component> out) {
        if (!c.isShowing()) return;
        if (c.getClass().getName().equals("com.jogamp.opengl.awt.GLJPanel")) out.add(c);
        if (c instanceof Container container) for (Component child : container.getComponents()) walk(child, out);
    }

    private static int meshChildren(Object source, Set<Object> visited) throws Exception {
        if (!visited.add(source)) return 0;
        if (source instanceof CArtMeshSource) return 1;
        int n = 0;
        for (Object child : (List<?>) call(source, "getDeformerChildren")) n += meshChildren(child, visited);
        return n;
    }

    private static void prepare() throws Exception {
        edt(() -> {
            int best = 0;
            for (var source : doc.getModelSource().getAllDeformers()) {
                if (!(source instanceof CWarpDeformerSource candidate)) continue;
                CWarpDeformer live = model.getWarpDeformer(candidate.getGuid());
                if (live == null || live.getCurrentKeyform() == null) continue;
                int children = meshChildren(candidate, new HashSet<>());
                if (children > best) { warp = candidate; best = children; }
            }
            require(warp != null && best > 0, "no editable Warp with child ArtMeshes");
            RESULT.setProperty("warp", warp.getGuid().getUuidString());
            RESULT.setProperty("descendantMeshes", Integer.toString(best));
            doc.getModelSource().setDefaultKeyformLocked(false);
            warp.setLocked(false);
            warp.setVisible(true);
            for (var parent : warp.getAllParents()) { parent.setLocked(false); parent.setVisible(true); }
            for (var parent : warp.getAllParentDeformers()) { parent.setLocked(false); parent.setVisible(true); }
            doc.getModelSource().updateVisibleAndLockHierarchy();
            // Preparation only; never invoked after drag, Undo or Redo.
            doc.getModelSource().updateModelInstances();
            doc.getSelector().clearSelection();
            doc.getSelector().select(warp.getSelection(), false);
            call(app, "command_showAllObject_InViewArea");
            return null;
        });
        frames(4);
    }

    private static void frames(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            long before = edt(() -> { long t = model.getLastUpdatedTimeMSec(); call(view, "repaintCanvas"); return t; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (edt(() -> model.getLastUpdatedTimeMSec()) <= before) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("no native model update after repaint");
                Thread.sleep(40);
            }
        }
    }

    private static int[] bits(Object form) throws Exception {
        float[] values = (float[]) call(form, "getPositions");
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return bits;
    }

    private static Map<String, int[]> snapshot() throws Exception {
        return edt(() -> {
            Map<String, int[]> data = new TreeMap<>();
            for (var source : doc.getModelSource().getAllDeformers()) {
                if (!(source instanceof CWarpDeformerSource w)) continue;
                for (Object form : w.getKeyforms()) data.put("source/warp/" + w.getGuid().getUuidString() + "/" + call(call(form, "getGuid"), "getUuidString"), bits(form));
                data.put("calculated/warp/" + w.getGuid().getUuidString(), bits(model.getWarpDeformer(w.getGuid()).getCalculatedForm()));
            }
            for (var mesh : model.getAllArtMeshes()) {
                String guid = (String) call(call(mesh.getSource(), "getGuid"), "getUuidString");
                data.put("calculated/mesh/" + guid, bits(mesh.getCalculatedForm()));
                for (Object form : mesh.getSource().getKeyforms()) data.put("source/mesh/" + guid + "/" + call(call(form, "getGuid"), "getUuidString"), bits(form));
            }
            return data;
        });
    }

    private static Map<String, int[]>[] runLeg(boolean enabled) throws Exception {
        return runLeg(enabled, enabled ? "on" : "off");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, int[]>[] runLeg(boolean enabled, String leg) throws Exception {
        System.setProperty(ENABLE, Boolean.toString(enabled));
        edt(() -> {
            // Match the point-selection and hover overlays before both A/B legs.
            doc.getSelector().clearSubSelection();
            doc.getSelector().clearHoveringObject();
            Class<?> mouse = Class.forName("com.live2d.ui.event.m");
            Object exit = mouse.getConstructor(MouseEvent.class).newInstance(new MouseEvent(
                    canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, -100, -100, 0, false, 0));
            call(view, "onInputEvent", exit);
            return null;
        });
        frames(4);
        long skipped = stat("meshSkipped");
        long failures = stat("failures");
        Map<String, int[]> before = snapshot();
        recordSnapshot(leg + "-before", before);
        capture(leg + "-before");
        edt(() -> { drag(); return null; });
        frames(3);
        Map<String, int[]> dragged = snapshot();
        recordSnapshot(leg + "-dragged", dragged);
        require(changes(before, dragged, "source/warp/" + warp.getGuid().getUuidString()) > 0, "drag did not mutate selected Warp source");
        require(changes(before, dragged, "calculated/mesh/") > 0, "drag did not affect any calculated child mesh");
        capture(leg + "-dragged");
        long modifiedBeforeUndo = edt(() -> ((Number) call(doc, "getLastModifiedTime")).longValue());
        edt(() -> { call(app, "command_undo", doc); return null; });
        frames(3);
        long modifiedAfterUndo = edt(() -> ((Number) call(doc, "getLastModifiedTime")).longValue());
        RESULT.setProperty(leg + ".undoDocumentTimestampChanged", Boolean.toString(modifiedBeforeUndo != modifiedAfterUndo));
        Map<String, int[]> undone = snapshot();
        recordSnapshot(leg + "-undo", undone);
        same(before, undone, leg + " Undo");
        capture(leg + "-undo");
        edt(() -> { call(app, "command_redo", doc); return null; });
        frames(3);
        Map<String, int[]> redone = snapshot();
        recordSnapshot(leg + "-redo", redone);
        same(dragged, redone, leg + " Redo");
        capture(leg + "-redo");
        edt(() -> { call(app, "command_undo", doc); return null; });
        frames(4);
        same(before, snapshot(), leg + " final Undo");
        long skipDelta = stat("meshSkipped") - skipped;
        RESULT.setProperty(leg + ".meshSkippedDelta", Long.toString(skipDelta));
        long failureDelta = stat("failures") - failures;
        RESULT.setProperty(leg + ".failuresDelta", Long.toString(failureDelta));
        require(failureDelta == 0, "incremental bridge failures occurred");
        if (enabled) require(skipDelta > 0, "incremental reuse was never exercised");
        RESULT.setProperty(leg + ".status", "PASS");
        return new Map[] {before, dragged, undone, redone};
    }

    private static void recordSnapshot(String name, Map<String, int[]> snapshot) throws Exception {
        StringBuilder text = new StringBuilder();
        for (var entry : snapshot.entrySet()) text.append(entry.getKey()).append('=').append(Arrays.toString(entry.getValue())).append('\n');
        byte[] data = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(output.resolve(name + "-coordinates.txt"), data);
        RESULT.setProperty(name + ".geometrySha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    }

    private static void capture(String name) throws Exception {
        java.awt.image.BufferedImage image = edt(() -> {
            var captured = new java.awt.image.BufferedImage(canvas.getWidth(), canvas.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = captured.createGraphics();
            try { canvas.paintAll(graphics); } finally { graphics.dispose(); }
            return captured;
        });
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        require(Arrays.stream(pixels).distinct().limit(16).count() >= 16, "empty or uniform GL canvas capture");
        javax.imageio.ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(pixels.length * Integer.BYTES);
        for (int pixel : pixels) bytes.putInt(pixel);
        RESULT.setProperty("captureBackend", "native GLJPanel.paintAll on EDT");
        RESULT.setProperty(name + ".pixelSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array())));
    }

    private static long stat(String name) {
        Object slot = System.getProperties().get("turboism.incremental-update.stats");
        if (!(slot instanceof Supplier<?> supplier)) throw new IllegalStateException("stats absent");
        return ((Number) ((Map<?, ?>) supplier.get()).get(name)).longValue();
    }

    private static int changes(Map<String, int[]> a, Map<String, int[]> b, String prefix) {
        return (int) a.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && !Arrays.equals(e.getValue(), b.get(e.getKey()))).count();
    }

    private static void same(Map<String, int[]> a, Map<String, int[]> b, String label) {
        require(a.keySet().equals(b.keySet()), label + " geometry set changed");
        for (String key : a.keySet()) require(Arrays.equals(a.get(key), b.get(key)), label + " coordinate mismatch: " + key);
    }

    private static void require(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }

    private static void drag() throws Exception {
        CWarpDeformer live = model.getWarpDeformer(warp.getGuid());
        float[] positions = live.getCalculatedForm().getPositions();
        GCameraManager camera = (GCameraManager) call(view, "getCameraManager");
        int chosen = -1, x = 0, y = 0;
        int points = positions.length / 2;
        int row = warp.getRow(), col = warp.getCol();
        int center = (row + 1) * (col + 1) == points ? (row / 2) * (col + 1) + col / 2 : points / 2;
        for (int offset = 0; offset < points; offset++) {
            int index = (center + offset) % points;
            GVector2 mapped = camera.documentToComponent(new GVector2(positions[index * 2], positions[index * 2 + 1]));
            if (mapped.getX() > 30 && mapped.getY() > 30 && mapped.getX() < canvas.getWidth() - 80 && mapped.getY() < canvas.getHeight() - 60) {
                chosen = index; x = Math.round(mapped.getX()); y = Math.round(mapped.getY()); break;
            }
        }
        require(chosen >= 0, "no visible Warp control point");
        RESULT.setProperty("drag.point", Integer.toString(chosen));
        RESULT.setProperty("drag.component", x + "," + y);
        Class<?> mouse = Class.forName("com.live2d.ui.event.m"), gesture = Class.forName("com.live2d.ui.event.l");
        Object hover = mouse.getConstructor(MouseEvent.class).newInstance(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false, 0));
        call(view, "onInputEvent", hover);
        Object drag = gesture.getConstructor(MouseEvent.class).newInstance(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        call(view, "onInputEvent", drag);
        for (int step = 1; step <= 8; step++) {
            MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x + step * 5, y + step * 3, 0, false, 0);
            gesture.getMethod("a", MouseEvent.class).invoke(drag, event);
            call(view, "onInputEvent", drag);
        }
        gesture.getMethod("b", MouseEvent.class).invoke(drag, new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x + 40, y + 24, 1, false, MouseEvent.BUTTON1));
        call(view, "onInputEvent", drag);
    }
}
