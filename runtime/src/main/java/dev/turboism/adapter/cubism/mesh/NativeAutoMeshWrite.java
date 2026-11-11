package dev.turboism.adapter.cubism.mesh;

import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.NO_PARAMS;
import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.call;
import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.declaredMethod;
import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.invoke;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.MeshEdgeKind;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Editable-mesh replacement in the exact shape the Editor's own Auto Mesh generator uses.
 *
 * <p>The Editor builds a fresh editable mesh, adds the canvas points and their triangle edges,
 * marks both parts updated, and copies the fresh content into the mesh the edit mode holds. It then
 * derives uvs, keyforms and triangulation from that mesh, so a writer that skips any of these steps
 * leaves the edit mode with a mesh the rest of the Editor cannot use.</p>
 *
 * <p>Every member below is the reviewed host contract; a missing member throws at the call site, so
 * a host that does not carry it never receives a half-written mesh.</p>
 */
final class NativeAutoMeshWrite {

    /** Kotlin default-argument bridge that performs the host's {@code copyFrom} contract. */
    static final String COPY_BRIDGE = "com.live2d.core.ICopyable$b";

    /** Kotlin default-argument bridge of the host's verifiable-source contract. */
    static final String VERIFY_BRIDGE = "com.live2d.util.verify.IVerifiable$a";

    /** Host contract of a source the Editor verifies after a topology write. */
    static final String VERIFIABLE = "com.live2d.util.verify.IVerifiable";

    /** Host contract that copies one editable mesh into another. */
    static final String COPYABLE = "com.live2d.core.ICopyable";

    /** Host extension that publishes a topology change to the model instances. */
    static final String TOPOLOGY_OBSERVER =
            "com.live2d.cubism.doc.model.extension.structureObserver.CTopologyObserverExtension";

    /** Every host class this write loads by name; the reviewed-artifact contract test resolves each one. */
    static final java.util.List<String> HOST_CLASS_NAMES =
            java.util.List.of(COPY_BRIDGE, VERIFY_BRIDGE, TOPOLOGY_OBSERVER, VERIFIABLE, COPYABLE);

    /** Point type the Editor gives every point it generates. */
    private static final String GENERATED_POINT_TYPE = "AUTO";

    /** Bit that asks a Kotlin default-argument bridge to supply the default context. */
    private static final int DEFAULT_CONTEXT_MASK = 2;

    /** Bit that asks {@code repaintCanvas$default} to supply the default surface. */
    private static final int DEFAULT_SURFACE_MASK = 1;

    private NativeAutoMeshWrite() {}

    /**
     * Replaces the content of one editable mesh with the canvas layout, in native order.
     *
     * @param target the editable mesh the edit mode holds
     * @param pointsXY the canvas points as {@code x0, y0, x1, y1, ...}
     * @param edgePairs the unique triangle edges as {@code first0, second0, ...}
     * @throws ReflectiveOperationException when the host cannot build or copy the mesh
     */
    static void replaceGeometry(final Object target, final float[] pointsXY, final int[] edgePairs)
            throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException("editable mesh");
        final Object fresh = freshMesh(target);
        addPoints(fresh, pointsXY, GENERATED_POINT_TYPE);
        addEdges(fresh, edgePairs);
        invoke(fresh, "setPositionUpdated");
        invoke(fresh, "setEdgeUpdated");
        copyInto(target, fresh);
    }

    /**
     * Verifies one ArtMesh source through the host's own verification entry.
     *
     * @param source the ArtMesh source to verify
     * @param complete {@code true} to verify every stored form, {@code false} for the source alone
     * @throws ReflectiveOperationException when the host carries no verification entry
     */
    static void verifySource(final Object source, final boolean complete) throws ReflectiveOperationException {
        final ClassLoader loader = source.getClass().getClassLoader();
        final Class<?> bridge = Class.forName(VERIFY_BRIDGE, false, loader);
        final Class<?> verifiable = loader.loadClass(VERIFIABLE);
        for (final Method candidate : MethodHandleCache.overloads(bridge, "a", 5)) {
            final Class<?>[] parameters = candidate.getParameterTypes();
            if (!Modifier.isStatic(candidate.getModifiers())
                    || candidate.getReturnType() != void.class
                    || parameters[0] != verifiable
                    || parameters[1] != boolean.class
                    || parameters[3] != int.class
                    || parameters[4] != Object.class) {
                continue;
            }
            candidate.invoke(null, source, complete, null, DEFAULT_CONTEXT_MASK, null);
            return;
        }
        throw new NoSuchMethodException(VERIFY_BRIDGE + "#verify");
    }

    /**
     * Recovers one ArtMesh source after a failed verification, as the Editor does.
     *
     * @param source the ArtMesh source to recover
     * @throws ReflectiveOperationException when the host carries no recovery entry
     */
    static void recoverSource(final Object source) throws ReflectiveOperationException {
        invoke(source, "recover");
    }

    /**
     * Marks the source's mesh as updated and publishes the change to the model.
     *
     * <p>This is the tail of the Editor's own generator: the mesh-updated flag, the topology
     * notification, and one canvas repaint. Without the notification the rest of the Editor keeps
     * the geometry it cached before the write.</p>
     *
     * @param source the ArtMesh source the write replaced
     * @param pack the host pack of the funnel call, or {@code null} to skip the repaint
     * @throws ReflectiveOperationException when the host carries no updated-flag entry
     */
    static void publishSourceChange(final Object source, final Object pack) throws ReflectiveOperationException {
        invoke(source, "setMeshUpdatedFlag__testImpl");
        final Object topology = topologyObserver(source);
        if (topology != null) {
            final Object model = currentModel(source);
            if (model != null) {
                invoke(topology, "fireTopologyChanged", model, true);
            }
        }
        repaint(pack);
    }

    /**
     * Announces the topology change before any geometry is replaced.
     *
     * <p>The Editor fires this before it builds the fresh mesh so observers drop their cached
     * geometry first. A best-effort step: a source without the observer still takes the write.</p>
     *
     * @param source the ArtMesh source the write will replace
     */
    static void fireTopologyWillChange(final Object source) {
        try {
            final Object topology = topologyObserver(source);
            final Object model = topology == null ? null : currentModel(source);
            if (model != null) {
                invoke(topology, "fireTopologyWillChange", model);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // The Editor's observers are optional; the write itself must still run.
        }
    }

    /**
     * Attaches the Undo snapshot of one editable mesh to the caller's group.
     *
     * @param label the Undo label
     * @param group the open host group
     * @param editableMesh the mesh the write will replace
     * @throws ReflectiveOperationException when the host cannot build the snapshot
     */
    static void snapshotMesh(final String label, final Object group, final Object editableMesh)
            throws ReflectiveOperationException {
        PopupMeshUndoContract.snapshotMeshes(
                editableMesh.getClass().getClassLoader(), label, group, List.of(editableMesh));
    }

    /**
     * Attaches the Undo snapshot of one ArtMesh to the caller's group.
     *
     * <p>Modeling mode reaches the snapshot through the ArtMesh handler, which captures the source
     * geometry the Editor later restores.</p>
     *
     * @param label the Undo label
     * @param group the open host group
     * @param source the ArtMesh source the write will replace
     * @throws ReflectiveOperationException when the host cannot build the snapshot
     */
    static void snapshotSource(final String label, final Object group, final Object source)
            throws ReflectiveOperationException {
        final Object handler = call(source, "getHandler", NO_PARAMS);
        if (handler == null) throw new NoSuchMethodException("ArtMesh handler");
        final Method snapshot = declaredMethod(handler.getClass(), "a", String.class);
        if (snapshot == null) throw new NoSuchMethodException("ArtMesh undo snapshot");
        final Object undo = snapshot.invoke(handler, label);
        final Method attach = declaredMethod(group.getClass(), "plusAssign", undo.getClass());
        if (attach == null) throw new NoSuchMethodException("ArtMesh undo attachment");
        attach.invoke(group, undo);
    }

    /** Builds the fresh mesh with the target's own guid, exactly as the Editor does. */
    private static Object freshMesh(final Object target) throws ReflectiveOperationException {
        final Object guid = call(target, "getMeshGuid", NO_PARAMS);
        final Constructor<?> constructor = guid == null
                ? target.getClass().getDeclaredConstructor()
                : target.getClass().getDeclaredConstructor(guid.getClass());
        ReflectAccess.open(constructor);
        return guid == null ? constructor.newInstance() : constructor.newInstance(guid);
    }

    private static void addPoints(final Object mesh, final float[] pointsXY, final String pointType)
            throws ReflectiveOperationException {
        for (int index = 0; index + 1 < pointsXY.length; index += 2) {
            if (NativeMeshMirrorGeometryOps.addPoint(mesh, pointsXY[index], pointsXY[index + 1], pointType) < 0) {
                throw new IllegalStateException("host did not return a valid point index");
            }
        }
    }

    private static void addEdges(final Object mesh, final int[] edgePairs) throws ReflectiveOperationException {
        for (int index = 0; index + 1 < edgePairs.length; index += 2) {
            final int first = edgePairs[index];
            final int second = edgePairs[index + 1];
            if (first == second) continue;
            NativeMeshMirrorGeometryOps.addEdge(mesh, new MeshEdgeRef(first, second, MeshEdgeKind.INNER));
        }
    }

    private static void copyInto(final Object target, final Object fresh) throws ReflectiveOperationException {
        final ClassLoader loader = target.getClass().getClassLoader();
        final Class<?> bridge = Class.forName(COPY_BRIDGE, false, loader);
        final Class<?> copyable = loader.loadClass(COPYABLE);
        for (final Method candidate : MethodHandleCache.overloads(bridge, "a", 5)) {
            final Class<?>[] parameters = candidate.getParameterTypes();
            if (!Modifier.isStatic(candidate.getModifiers())
                    || candidate.getReturnType() != void.class
                    || parameters[0] != copyable
                    || parameters[1] != copyable
                    || parameters[3] != int.class
                    || parameters[4] != Object.class) {
                continue;
            }
            candidate.invoke(null, target, fresh, null, DEFAULT_CONTEXT_MASK, null);
            return;
        }
        throw new NoSuchMethodException(COPY_BRIDGE + "#copyFrom");
    }

    private static Object topologyObserver(final Object source) throws ReflectiveOperationException {
        final Object extensions = call(source, "getExtensions", NO_PARAMS);
        if (!(extensions instanceof Iterable<?> values)) return null;
        final ClassLoader loader = source.getClass().getClassLoader();
        final Class<?> observer = Class.forName(TOPOLOGY_OBSERVER, false, loader);
        for (final Object extension : values) {
            if (extension == null) continue;
            if (observer.isInstance(extension)) return extension;
        }
        return null;
    }

    private static Object currentModel(final Object source) throws ReflectiveOperationException {
        final Object modelSource = call(source, "getModelSource", NO_PARAMS);
        return modelSource == null ? null : call(modelSource, "getCurrentInstance", NO_PARAMS);
    }

    private static void repaint(final Object pack) {
        if (pack == null) return;
        try {
            final Object updateManager = call(pack, "getUpdateManager", NO_PARAMS);
            if (updateManager == null) return;
            final Method direct = declaredMethod(updateManager.getClass(), "repaintCanvas", NO_PARAMS);
            if (direct != null) {
                direct.invoke(updateManager);
                return;
            }
            for (final Method candidate :
                    MethodHandleCache.overloads(updateManager.getClass(), "repaintCanvas$default", 3)) {
                if (Modifier.isStatic(candidate.getModifiers())) {
                    candidate.invoke(null, updateManager, null, DEFAULT_SURFACE_MASK, null);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // A repaint fault must not turn a committed mesh write into a refusal.
        }
    }
}
