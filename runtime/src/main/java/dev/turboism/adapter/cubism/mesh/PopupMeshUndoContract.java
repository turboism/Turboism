package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Popup mesh undo selectors recovered from the exact reviewed 5.3.02 artifact. */
final class PopupMeshUndoContract {
    private static final String PACK = "com.live2d.cubism.view.context.actionManager.X";
    private static final String MODELING_PACK = "com.live2d.cubism.view.context.actionManager.Z";
    private static final String MODELING_MAIN_PACK = "com.live2d.cubism.view.context.actionManager.aG";
    private static final ClassValue<Boolean> ADMITTED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            if (!PACK.equals(type.getName())
                    && !MODELING_PACK.equals(type.getName())
                    && !MODELING_MAIN_PACK.equals(type.getName())) return false;
            try {
                final var source = type.getProtectionDomain().getCodeSource();
                return source != null
                        && ReviewedHostArtifacts.CUBISM_5_3_02.equals(HostArtifactDigest.from(
                                Path.of(source.getLocation().toURI())));
            } catch (Exception failure) {
                return false;
            }
        }
    };

    private PopupMeshUndoContract() {}

    static boolean admitted(final Object pack) {
        return pack != null && PACK.equals(pack.getClass().getName()) && ADMITTED.get(pack.getClass());
    }

    /** Rebuilds the envelope after a mode change without dispatching the cached input event. */
    static Object refresh(final Object viewContext, final Object cached) throws ReflectiveOperationException {
        if (cached == null || !ADMITTED.get(cached.getClass())) return cached;
        if (cached.getClass().getMethod("ab").invoke(cached) != viewContext) return cached;
        final ClassLoader loader = cached.getClass().getClassLoader();
        final Object event = cached.getClass().getMethod("ad").invoke(cached);
        final Object position = cached.getClass().getMethod("ae").invoke(cached);
        if (event == null) return cached;
        return viewContext
                .getClass()
                .getMethod(
                        "createActionPack",
                        loader.loadClass("com.live2d.ui.event.h"),
                        loader.loadClass("com.live2d.type.CPoint"))
                .invoke(viewContext, event, position);
    }

    static boolean owns(final Object pack, final Object mode) throws ReflectiveOperationException {
        return admitted(pack) && pack.getClass().getMethod("c").invoke(pack) == mode;
    }

    static void snapshot(final Object pack, final String label, final Object group)
            throws ReflectiveOperationException {
        if (!admitted(pack)) throw new IllegalStateException("popup mesh undo host is not admitted");
        final ClassLoader loader = pack.getClass().getClassLoader();
        final Object entries = pack.getClass().getMethod("a").invoke(pack);
        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("popup mesh undo has no live meshes");
        }
        final List<Object> meshes = new ArrayList<>();
        for (Object entry : list) meshes.add(entry.getClass().getMethod("b").invoke(entry));
        snapshotMeshes(loader, label, group, meshes);
    }

    static void snapshotMeshes(
            final ClassLoader loader, final String label, final Object group, final List<Object> meshes)
            throws ReflectiveOperationException {
        if (meshes.isEmpty()) throw new IllegalStateException("popup mesh undo has no live meshes");
        final Class<?> copyable = loader.loadClass("com.live2d.core.ICopyable");
        final Class<?> copyContext = loader.loadClass("com.live2d.core.a");
        final Class<?> marker = loader.loadClass("kotlin.jvm.internal.DefaultConstructorMarker");
        final Class<?> meshType = loader.loadClass("com.live2d.graphics3d.editableMesh.GEditableMesh2");
        final Class<?> function = loader.loadClass("kotlin.jvm.functions.Function1");
        final Class<?> undoType = loader.loadClass("com.live2d.undo.SimpleUndo");
        final Class<?> undoBase = loader.loadClass("com.live2d.undo.ACUndoable");
        final Class<?> listenerType = loader.loadClass("com.live2d.cubism.view.context.action.action_meshEditor.b");
        final var constructor = undoType.getConstructor(String.class, copyable, copyContext, int.class, marker);
        final var listener = listenerType.getDeclaredConstructor(meshType);
        listener.setAccessible(true);
        final var addListener = undoType.getMethod("addUndoRedoListener", function);
        final var addEdit = group.getClass().getMethod("addEdit", undoBase, boolean.class);
        // Construct all snapshots before registering any edit or mutating geometry.
        final List<Object> snapshots = new ArrayList<>();
        for (Object mesh : meshes) {
            final Object undo = constructor.newInstance(label, mesh, null, 4, null);
            addListener.invoke(undo, listener.newInstance(mesh));
            snapshots.add(undo);
        }
        for (Object undo : snapshots) {
            if (!Boolean.TRUE.equals(addEdit.invoke(group, undo, false))) {
                throw new IllegalStateException("popup mesh undo snapshot was refused");
            }
        }
    }
}
