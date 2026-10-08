import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.SwingUtilities;

/** Separate task-fixture diagnostic. Uses native selection/commands, never cached-version setters. */
final class NativeAutoConnect {
    private static final String MAIN = "com.live2d.cubism.doc.modeling.CModelingEditMode_Main";
    private static final String EDIT = "com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor";
    private NativeAutoConnect() {}

    record MeshResult(String sourceId, MeshResultSnapshot.Result result) {}

    static List<String> enter(Object controller, Binding binding) throws Exception {
        Object doc = boundDocument(controller, binding);
        Object mode = call(doc, "getCurrentEditMode");
        require(mode.getClass().getName().equals(MAIN), "requires main modeling mode");
        Object selector = call(mode, "getSelector");
        List<?> sources = list(call(call(doc, "getModelSource"), "getAllArtMeshes"));
        List<Object> eligible = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Object source : sources) {
            if (Boolean.TRUE.equals(call(source, "isLockedInHierarchy"))) continue;
            Object extension = call(source, "getEditableMeshExtension");
            if (extension == null || Boolean.TRUE.equals(call(extension, "isLocked"))) continue;
            Object mesh = call(extension, "getEditableMesh");
            if (!(call(mesh, "getPointCount") instanceof Integer count) || count < 3) continue;
            eligible.add(source);
            ids.add(call(source, "getId").toString());
        }
        require(!eligible.isEmpty(), "no eligible task meshes");
        require(ids.stream().distinct().count() == ids.size(), "duplicate source IDs");
        call(selector, "clearSelection");
        for (Object source : eligible) invokeNamed(selector, "addSelected", source, 0);
        require(sameIdentities(eligible, list(call(selector, "getSelectedArtMeshes"))), "selection mismatch");
        Object started = invokeNamed(controller, "command_startMeshEditor", doc, false);
        require(Boolean.TRUE.equals(started), "native mesh editor did not start");
        require(boundDocument(controller, binding) == doc, "document switched during entry");
        Object edit = call(doc, "getCurrentEditMode");
        require(edit.getClass().getName().equals(EDIT), "native mesh mode absent");
        List<Object> editedSources = new ArrayList<>();
        for (Object data : list(call(edit, "getEditDataList"))) editedSources.add(call(data, "a"));
        require(sameIdentities(eligible, editedSources), "edit data source mismatch");
        return List.copyOf(ids);
    }

    static void connect(Object controller, Binding binding, boolean rebuild, boolean preserveBorder)
            throws Exception {
        Object doc = boundDocument(controller, binding);
        Object mode = call(doc, "getCurrentEditMode");
        require(mode.getClass().getName().equals(EDIT), "requires native mesh mode");
        require(!list(call(mode, "getEditDataList")).isEmpty(), "empty native edit data");
        Class<?> manual = Class.forName(
            "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual",
            false, mode.getClass().getClassLoader());
        Object panel = callReturning(manual.getField("INSTANCE").get(null), "getToolPanel",
            "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolPanel_MeshEdit");
        Object rebuildBox = call(panel, "getCheckboxRebuildMesh");
        Object borderBox = call(panel, "getCheckboxSaveBorderOfEdge");
        invokeNamed(rebuildBox, "setSelected", rebuild);
        invokeNamed(borderBox, "setSelected", preserveBorder);
        require(Boolean.valueOf(rebuild).equals(call(rebuildBox, "isSelected")), "rebuild option mismatch");
        require(Boolean.valueOf(preserveBorder).equals(call(borderBox, "isSelected")), "border option mismatch");
        invokeNamed(controller, "command_meshEditConnectAuto", doc);
        require(boundDocument(controller, binding) == doc && call(doc, "getCurrentEditMode") == mode,
            "native context changed during auto-connect");
    }

    /** Call in a later EDT observation after native repaint, not by forcing cache generation. */
    static List<MeshResult> capture(Object controller, Binding binding, List<String> expectedIds)
            throws Exception {
        Object mode = call(boundDocument(controller, binding), "getCurrentEditMode");
        require(mode.getClass().getName().equals(EDIT), "mesh mode ended before capture");
        List<MeshResult> results = new ArrayList<>();
        for (Object data : list(call(mode, "getEditDataList"))) {
            String id = call(call(data, "a"), "getId").toString();
            results.add(new MeshResult(id, MeshResultSnapshot.capture(call(data, "b"))));
        }
        require(results.stream().map(MeshResult::sourceId).toList().equals(expectedIds),
            "edit data order/identity differs from selected source IDs");
        return List.copyOf(results);
    }

    static int leave(Object controller, Binding binding, java.awt.Window main) throws Exception {
        Object doc = boundDocument(controller, binding);
        require(call(doc, "getCurrentEditMode").getClass().getName().equals(EDIT),
            "native mesh mode missing before cancel");
        // Exact reviewed native cancel resource, resolved from the same locale source as editCancel.
        Class<?> locale = Class.forName("b.c", false, controller.getClass().getClassLoader());
        Object message = invokeNamed(locale.getField("a").get(null), "a", "CUB3-0009", (Object) new String[0]);
        require(message instanceof String && !message.equals("CUB3-0009"), "native cancel resource unavailable");
        int answers;
        try (NativeCancelPrompt prompt = new NativeCancelPrompt(main, (String) message, () -> {
            require(boundDocument(controller, binding) == doc
                    && call(doc, "getCurrentEditMode").getClass().getName().equals(EDIT),
                "task context changed during cancel prompt");
        }, 10_000)) {
            invokeNamed(controller, "command_cancelMeshEditor", doc);
            answers = prompt.requireSuccess();
        }
        require(boundDocument(controller, binding) == doc
                && call(doc, "getCurrentEditMode").getClass().getName().equals(MAIN),
            "native mesh cancel did not restore main mode");
        return answers;
    }

    /** Per-driver binding, never stored globally. Filesystem I/O is prohibited on the EDT. */
    static final class Binding {
        private final Object document;
        private final File capturedFile;
        private final String path;
        private volatile boolean verified;
        private Binding(Object document, File file) {
            this.document = document;
            capturedFile = file;
            path = file.getPath();
        }
        void verifyFixture(File fixture) throws Exception {
            require(!SwingUtilities.isEventDispatchThread(), "fixture canonicalization must run off EDT");
            require(!verified, "binding already verified");
            require(capturedFile.getCanonicalFile().equals(fixture.getCanonicalFile()),
                "active document is not task fixture");
            verified = true;
        }
    }

    static Binding binding(Object controller) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "document binding requires EDT");
        Object doc = call(controller, "getCurrentDoc");
        Object file = call(call(doc, "getFileContent"), "getFile");
        require(file instanceof File, "missing native document file");
        return new Binding(doc, (File) file);
    }

    static Object boundDocument(Object controller, Binding binding) throws Exception {
        require(SwingUtilities.isEventDispatchThread(), "native command requires EDT");
        require(binding != null && binding.verified, "unverified task binding");
        Object doc = call(controller, "getCurrentDoc");
        require(doc == binding.document, "active document identity changed");
        Object file = call(call(doc, "getFileContent"), "getFile");
        require(file instanceof File && ((File) file).getPath().equals(binding.path),
            "active document path changed");
        return doc;
    }

    static boolean sameIdentities(List<?> expected, List<?> actual) {
        IdentityHashMap<Object, Boolean> identities = new IdentityHashMap<>();
        for (Object value : expected) if (identities.put(value, Boolean.TRUE) != null) return false;
        for (Object value : actual) if (identities.remove(value) == null) return false;
        return identities.isEmpty();
    }

    private static List<?> list(Object value) {
        require(value instanceof List<?>, "unexpected native list");
        return (List<?>) value;
    }

    private static Object call(Object receiver, String name) throws Exception {
        require(receiver != null, "missing native object: " + name);
        return invoke(receiver.getClass().getMethod(name), receiver);
    }

    // Kotlin/JVM may expose same-name, same-argument methods with different return descriptors.
    // Select the descriptor used by the reviewed native command rather than Class.getMethod.
    static Object callReturning(Object receiver, String name, String returnType) throws Exception {
        Method selected = null;
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 0
                    || !method.getReturnType().getName().equals(returnType) || method.isBridge()) continue;
            require(selected == null, "ambiguous native return descriptor: " + name);
            selected = method;
        }
        require(selected != null, "missing native return descriptor: " + name + ":" + returnType);
        return invoke(selected, receiver);
    }

    static Object invokeNamed(Object receiver, String name, Object... args) throws Exception {
        Method selected = null;
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length || method.isBridge()) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = types[i] == boolean.class ? Boolean.class : types[i] == int.class ? Integer.class : types[i];
                matches &= args[i] != null && type.isInstance(args[i]);
            }
            if (!matches) continue;
            require(selected == null, "ambiguous native command: " + name);
            selected = method;
        }
        require(selected != null, "missing native command: " + name);
        return invoke(selected, receiver, args);
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("native invocation failed", cause);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
