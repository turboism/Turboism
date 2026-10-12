package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Popup mesh undo selectors recovered from reviewed Cubism host artifacts. */
final class PopupMeshUndoContract {
    private static final String PACK = "com.live2d.cubism.view.context.actionManager.X";
    private static final String MODELING_PACK = "com.live2d.cubism.view.context.actionManager.Z";
    private static final String MODELING_MAIN_PACK = "com.live2d.cubism.view.context.actionManager.aG";

    private static final class Access {
        final Method viewContext;
        final Method event;
        final Method position;
        final Method mode;
        final Method entries;
        final Method entryMesh;

        Access(final Class<?> type) throws ReflectiveOperationException {
            Method vc = null;
            Method ev = null;
            Method pt = null;
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() != 0) continue;
                final String ret = method.getReturnType().getName();
                if ("com.live2d.cubism.view.context.CEViewContext".equals(ret)) {
                    vc = method;
                } else if ("com.live2d.ui.event.h".equals(ret)) {
                    ev = method;
                } else if ("com.live2d.type.CPoint".equals(ret)) {
                    pt = method;
                }
            }
            if (vc == null || ev == null || pt == null) {
                throw new NoSuchMethodException("action pack accessors");
            }
            this.viewContext = vc;
            this.event = ev;
            this.position = pt;
            this.mode = type.getMethod("c");
            this.entries = type.getMethod("a");
            final Class<?> entryType = type.getClassLoader()
                    .loadClass("com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor$b");
            this.entryMesh = entryType.getMethod("b");
        }
    }

    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(final Class<?> type) {
            if (!PACK.equals(type.getName())
                    && !MODELING_PACK.equals(type.getName())
                    && !MODELING_MAIN_PACK.equals(type.getName())) return null;
            try {
                final var source = type.getProtectionDomain().getCodeSource();
                if (source == null) return null;
                final var digest = HostArtifactDigest.from(Path.of(source.getLocation().toURI()));
                if (ReviewedHostArtifacts.cubismVersionOf(digest).isEmpty()) return null;
                return new Access(type);
            } catch (Exception failure) {
                return null;
            }
        }
    };

    private PopupMeshUndoContract() {}

    static boolean admitted(final Object pack) {
        return pack != null && PACK.equals(pack.getClass().getName()) && ACCESS.get(pack.getClass()) != null;
    }

    /** Rebuilds the envelope after a mode change without dispatching the cached input event. */
    static Object refresh(final Object viewContext, final Object cached) throws ReflectiveOperationException {
        if (cached == null) return null;
        final Access access = ACCESS.get(cached.getClass());
        if (access == null) return cached;
        if (access.viewContext.invoke(cached) != viewContext) return cached;
        final ClassLoader loader = cached.getClass().getClassLoader();
        final Object event = access.event.invoke(cached);
        final Object position = access.position.invoke(cached);
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
        if (!admitted(pack)) return false;
        final Access access = ACCESS.get(pack.getClass());
        return access != null && access.mode.invoke(pack) == mode;
    }

    static void snapshot(final Object pack, final String label, final Object group)
            throws ReflectiveOperationException {
        if (!admitted(pack)) throw new IllegalStateException("popup mesh undo host is not admitted");
        final Access access = ACCESS.get(pack.getClass());
        if (access == null) throw new IllegalStateException("popup mesh undo access is unavailable");
        final ClassLoader loader = pack.getClass().getClassLoader();
        final Object entries = access.entries.invoke(pack);
        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("popup mesh undo has no live meshes");
        }
        final List<Object> meshes = new ArrayList<>();
        for (Object entry : list) meshes.add(access.entryMesh.invoke(entry));
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
