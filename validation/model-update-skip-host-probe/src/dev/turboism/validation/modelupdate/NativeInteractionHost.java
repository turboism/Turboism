package dev.turboism.validation.modelupdate;

import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Exact-host test access: reads state, selects a target, and invokes native Undo/Redo. */
final class NativeInteractionHost {
    record Geometry(List<float[]> interpolated, List<float[]> source, List<float[]> calculated,
                    List<List<float[]>> keyforms) { }
    record Camera(float scale, float x, float y, float z) { }
    private final String fixture;
    private final Frame window;
    private final JComponent canvas;
    private final Object app, doc, view, model, manager, camera, selector, undo;
    private final Class<?> documentType, sourceType, vector2Type;
    private final List<?> meshes, originalSelection;
    private final int width, height;
    private Object targetSource;
    private int targetIndex = -1;

    NativeInteractionHost(String fixture, Frame window, JComponent canvas) throws Exception {
        requireEdt();
        this.fixture = fixture; this.window = window; this.canvas = canvas;
        width = canvas.getWidth(); height = canvas.getHeight();
        ClassLoader loader = canvas.getClass().getClassLoader();
        Class<?> appType = Class.forName("com.live2d.cubism.CEAppCtrl", false, loader);
        documentType = Class.forName("com.live2d.cubism.doc.IDocument", false, loader);
        sourceType = Class.forName("com.live2d.cubism.doc.model.ACParameterControllableSource", false, loader);
        vector2Type = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);
        app = appType.getMethod("access$get_instance$cp").invoke(null);
        doc = call(app, "getCurrentDoc"); view = call(app, "getCurrentViewContext");
        if (doc == null || !doc.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument")) {
            throw new IllegalStateException("not a modeling document");
        }
        model = call(view, "getModel"); manager = call(view, "getCameraManager");
        camera = call(manager, "getCameraWrapper"); selector = call(doc, "getSelector");
        undo = call(doc, "getUndoManager");
        meshes = List.copyOf(list(call(model, "getAllArtMeshes")));
        originalSelection = List.copyOf(list(call(selector, "getSelected")));
        check();
        if (meshes.isEmpty()) throw new IllegalStateException("fixture has no ArtMeshes");
    }

    void check() throws Exception {
        requireEdt();
        if (!window.isShowing() || !window.getTitle().contains(fixture)
            || !canvas.isShowing() || SwingUtilities.getWindowAncestor(canvas) != window
            || canvas.getWidth() != width || canvas.getHeight() != height
            || call(app, "getCurrentDoc") != doc || call(app, "getCurrentViewContext") != view
            || call(view, "getDoc") != doc || call(view, "getModel") != model
            || list(call(app, "getAllDocs")).size() != 1
            || !(call(doc, "getFile") instanceof java.io.File file) || !file.getName().equals(fixture)) {
            throw new IllegalStateException("task document/window identity changed");
        }
    }

    Geometry geometry() throws Exception {
        check();
        List<?> current = list(call(model, "getAllArtMeshes"));
        if (current.size() != meshes.size()) throw new IllegalStateException("mesh inventory changed");
        List<float[]> evaluated = new ArrayList<>(), authoring = new ArrayList<>(), calculated = new ArrayList<>();
        List<List<float[]>> keyforms = new ArrayList<>();
        for (int i = 0; i < meshes.size(); i++) {
            Object mesh = meshes.get(i);
            if (mesh != current.get(i)) throw new IllegalStateException("mesh identity changed");
            evaluated.add(((float[]) call(call(mesh, "getInterpolatedForm"), "getPositions")).clone());
            Object source = call(mesh, "getSource");
            authoring.add(((float[]) call(source, "getPositions")).clone());
            calculated.add(((float[]) call(call(mesh, "getCalculatedForm"), "getPositions")).clone());
            List<float[]> forms = new ArrayList<>();
            for (Object form : list(call(source, "getKeyforms"))) {
                forms.add(((float[]) call(form, "getPositions")).clone());
            }
            keyforms.add(List.copyOf(forms));
        }
        return new Geometry(List.copyOf(evaluated), List.copyOf(authoring), List.copyOf(calculated), List.copyOf(keyforms));
    }
    static List<Integer> changed(Geometry first, Geometry second) {
        if (first.interpolated().size() != second.interpolated().size()
            || first.source().size() != second.source().size()) throw new IllegalStateException("geometry inventory differs");
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < first.interpolated().size(); i++) {
            if (!NativeDragSequence.same(first.interpolated().get(i), second.interpolated().get(i))
                || !NativeDragSequence.same(first.source().get(i), second.source().get(i))
                || !NativeDragSequence.same(first.calculated().get(i), second.calculated().get(i))
                || !sameForms(first.keyforms().get(i), second.keyforms().get(i))) changed.add(i);
        }
        return changed;
    }
    private static boolean sameForms(List<float[]> first, List<float[]> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!NativeDragSequence.same(first.get(i), second.get(i))) return false;
        return true;
    }
    static boolean wholeMeshChanged(Geometry first, Geometry second, int target) {
        if (NativeDragSequence.allVerticesMoved(first.calculated().get(target), second.calculated().get(target))) return true;
        List<float[]> before = first.keyforms().get(target), after = second.keyforms().get(target);
        if (before.size() != after.size()) return false;
        boolean all = false;
        for (int i = 0; i < before.size(); i++) {
            if (!NativeDragSequence.same(before.get(i), after.get(i))) {
                if (!NativeDragSequence.allVerticesMoved(before.get(i), after.get(i))) return false;
                all = true;
            }
        }
        return all;
    }
    Camera camera() throws Exception {
        check(); Object look = call(camera, "getCameraLookAt");
        return new Camera(number(call(camera, "getCameraScale")), number(call(look, "getX")),
            number(call(look, "getY")), number(call(look, "getZ")));
    }
    void restoreCamera(Camera saved) throws Exception {
        check();
        Object look = call(camera, "getCameraLookAt");
        call(look, "set", new Class<?>[] {float.class, float.class, float.class}, saved.x(), saved.y(), saved.z());
        call(camera, "setCameraScale", new Class<?>[] {float.class}, saved.scale());
        call(manager, "updateCamera", new Class<?>[] {boolean.class}, true);
        call(view, "repaintCanvas");
    }
    int undoPosition() throws Exception { check(); return ((Number) call(undo, "getCurrentPos")).intValue(); }
    List<Object> appliedAuthoringEdits() throws Exception {
        check();
        List<?> history = list(call(undo, "getUndoList"));
        int position = undoPosition();
        if (position < 0 || position > history.size()) throw new IllegalStateException("invalid native undo cursor");
        List<Object> edits = new ArrayList<>();
        for (int i = 0; i < position; i++) {
            Object edit = history.get(i);
            if (Boolean.TRUE.equals(call(edit, "isSignificant"))) edits.add(edit);
        }
        return List.copyOf(edits);
    }
    String undoState() throws Exception {
        check(); return call(undo, "getCurrentPos") + ":" + list(call(undo, "getUndoList")).size()
            + ":" + call(undo, "getEditCount");
    }
    boolean modified() throws Exception { check(); return Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")); }
    void undo() throws Exception { check(); call(app, "command_undo", new Class<?>[] {documentType}, doc); }
    void redo() throws Exception { check(); call(app, "command_redo", new Class<?>[] {documentType}, doc); }
    String sceneInventory() throws Exception {
        check();
        java.util.Map<Integer, Integer> depths = new java.util.TreeMap<>();
        long total = 0L;
        int maximum = 0;
        for (Object mesh : meshes) {
            java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Object parent = call(mesh, "getParentEntity");
            int depth = 0;
            while (parent != null) {
                if (!seen.add(parent) || depth >= 1024) throw new IllegalStateException("invalid scene parent chain");
                depth++;
                parent = call(parent, "getParentEntity");
            }
            depths.merge(depth, 1, Integer::sum);
            maximum = Math.max(maximum, depth); total += depth;
        }
        return "scene.artMeshes=" + meshes.size() + "\nscene.parentDepthHistogram=" + depths
            + "\nscene.parentDepthMaximum=" + maximum + "\nscene.parentDepthMean=" + (double) total / meshes.size() + "\n";
    }
    String actionState() throws Exception {
        check();
        Object action = call(call(view, "getActionManager"), "getCurAction");
        List<?> history = list(call(undo, "getUndoList"));
        String last = history.isEmpty() ? "none" : String.valueOf(call(history.get(history.size() - 1), "getUndoName"));
        return "action=" + (action == null ? "none" : action.getClass().getName())
            + "\nundoName=" + last.replace('\n', ' ').replace('\r', ' ')
            + "\nmode=" + call(view, "getCurrentEditMode").getClass().getName()
            + "\ndrawableEditable=" + call(view, "isDrawableEditable") + "\n";
    }
    int targetIndex() { return targetIndex; }
    String targetId() throws Exception { return String.valueOf(call(targetSource, "getId")).replace('\n', ' ').replace('\r', ' '); }

    /** Chooses an on-screen triangle and verifies it with the native canvas hit test. */
    List<Point> candidatePoints() throws Exception {
        check();
        List<Point> candidates = new ArrayList<>();
        for (int m = meshes.size() - 1; m >= 0; m--) {
            Object mesh = meshes.get(m), source = call(mesh, "getSource");
            if (!Boolean.TRUE.equals(call(source, "isVisibleInHierarchy"))
                || !Boolean.TRUE.equals(call(source, "isEditableInHierarchy"))
                || call(mesh, "getCurrentKeyform") == null
                || Boolean.TRUE.equals(call(source, "isLockedInHierarchy"))
                || number(call(call(mesh, "getCalculatedForm"), "getOpacity")) <= 0.01f) continue;
            float[] positions = (float[]) call(mesh, "getPositions");
            int[] indices = (int[]) call(mesh, "getIndices");
            for (int t = 0; t + 2 < indices.length; t += 3) {
                int a = indices[t] * 2, b = indices[t + 1] * 2, c = indices[t + 2] * 2;
                if (a < 0 || b < 0 || c < 0 || a + 1 >= positions.length
                    || b + 1 >= positions.length || c + 1 >= positions.length) continue;
                Object pa = vector(positions[a], positions[a + 1]);
                Object pb = vector(positions[b], positions[b + 1]);
                Object pc = vector(positions[c], positions[c + 1]);
                Object sa = call(manager, "documentToComponent", new Class<?>[] {vector2Type}, pa);
                Object sb = call(manager, "documentToComponent", new Class<?>[] {vector2Type}, pb);
                Object sc = call(manager, "documentToComponent", new Class<?>[] {vector2Type}, pc);
                float ax = number(call(sa, "getX")), ay = number(call(sa, "getY"));
                float bx = number(call(sb, "getX")), by = number(call(sb, "getY"));
                float cx = number(call(sc, "getX")), cy = number(call(sc, "getY"));
                if (Math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) < 100f) continue;
                int x = Math.round((ax + bx + cx) / 3f), y = Math.round((ay + by + cy) / 3f);
                if (x < 40 || y < 40 || x >= width - 40 || y >= height - 40) continue;
                Object center = vector((positions[a] + positions[b] + positions[c]) / 3f,
                    (positions[a + 1] + positions[b + 1] + positions[c + 1]) / 3f);
                if (call(mesh, "getPointInTriangle_onCanvas", new Class<?>[] {vector2Type, boolean.class}, center, true) == null) continue;
                Point candidate = new Point(x, y);
                if (candidates.stream().noneMatch(p -> p.distanceSq(candidate) < 36.0)) candidates.add(candidate);
                if (candidates.size() >= 96) return List.copyOf(candidates);
            }
        }
        if (candidates.isEmpty()) throw new IllegalStateException("no visible unlocked on-screen ArtMesh triangle meets drag bounds");
        return List.copyOf(candidates);
    }
    void clearSelection() throws Exception {
        check(); call(selector, "clearSelection");
        call(view, "updateBoundingBox"); call(view, "repaintCanvas");
    }
    boolean adoptSelectedTarget() throws Exception {
        check(); List<?> selected = list(call(selector, "getSelectedArtMeshes"));
        if (selected.size() != 1 || !list(call(selector, "getSelectedDeformers")).isEmpty()) {
            return false;
        }
        targetSource = selected.get(0); targetIndex = -1;
        for (int i = 0; i < meshes.size(); i++) if (call(meshes.get(i), "getSource") == targetSource) targetIndex = i;
        if (targetIndex < 0) throw new IllegalStateException("selected mesh not in original model");
        if (call(meshes.get(targetIndex), "getCurrentKeyform") == null
            || !Boolean.TRUE.equals(call(targetSource, "isEditableInHierarchy"))) return false;
        requireTargetSelected();
        return true;
    }
    void requireTargetSelected() throws Exception {
        check(); List<?> selected = list(call(selector, "getSelectedArtMeshes"));
        if (selected.size() != 1 || selected.get(0) != targetSource
            || !list(call(selector, "getSelectedDeformers")).isEmpty()) {
            throw new IllegalStateException("gesture is not selecting exactly the task ArtMesh");
        }
    }
    void restoreSelection() throws Exception {
        check();
        call(selector, "clearAndAddAllSelected", new Class<?>[] {List.class, int.class}, originalSelection, 0);
        call(view, "updateBoundingBox"); call(view, "repaintCanvas");
    }
    private Object vector(float x, float y) throws Exception {
        return vector2Type.getConstructor(float.class, float.class).newInstance(x, y);
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> items)) throw new IllegalStateException("native list contract differs");
        return items;
    }
    private static float number(Object value) { return ((Number) value).floatValue(); }
    static Object call(Object receiver, String name) throws Exception { return call(receiver, name, new Class<?>[0]); }
    static Object call(Object receiver, String name, Class<?>[] types, Object... args) throws Exception {
        requireEdt();
        try { return receiver.getClass().getMethod(name, types).invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) throw new IllegalStateException("native authoring checks require EDT");
    }
}
