package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Method-local native edge lookup. Only an extended shared admission may enter.
 * The admitted plan must prove the native callback/mutation closure separately;
 * list size and identity checks here are defensive checks, not a mutation lease.
 * The scope adds no synchronization or guarantee for concurrent external list writers.
 */
public final class NativeMeshEdgeLookup {
    private static final String MESH = "com.live2d.graphics3d.editableMesh.GEditableMesh2";
    private static final String LIST = "com.live2d.type.CArrayList";
    private static final String EDGE = "com.live2d.graphics3d.editableMesh.MEdge";
    private static final StackWalker CALLERS = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            try {
                ClassLoader loader = type.getClassLoader();
                Class<?> list = Class.forName(LIST, false, loader), edge = Class.forName(EDGE, false, loader);
                if (!MESH.equals(type.getName()) || list.getClassLoader() != loader || edge.getClassLoader() != loader)
                    throw new IllegalArgumentException("native loader identity");
                Field edges = type.getDeclaredField("_edges"), indices = type.getDeclaredField("cached_indices");
                if (edges.getType() != list || indices.getType() != int[].class)
                    throw new IllegalArgumentException("native field identity");
                edges.setAccessible(true);
                indices.setAccessible(true);
                Method first = edge.getDeclaredMethod("getIndex1"), second = edge.getDeclaredMethod("getIndex2");
                if (first.getReturnType() != int.class || second.getReturnType() != int.class)
                    throw new IllegalArgumentException("native endpoint identity");
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodType objectGetter = MethodType.methodType(Object.class, Object.class);
                MethodType intGetter = MethodType.methodType(int.class, Object.class);
                return new Access(
                        list,
                        edge,
                        lookup.unreflectGetter(edges).asType(objectGetter),
                        lookup.unreflectGetter(indices).asType(objectGetter),
                        lookup.unreflect(first).asType(intGetter),
                        lookup.unreflect(second).asType(intGetter));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("native access unavailable", failure);
            }
        }
    };

    private NativeMeshEdgeLookup() {}

    private record Access(
            Class<?> list,
            Class<?> edge,
            MethodHandle edges,
            MethodHandle indices,
            MethodHandle first,
            MethodHandle second) {}

    private static final class Scope implements AutoCloseable {
        final Object mesh;
        final List<?> list;
        final Access access;
        final AutoCloseable definition;
        final Scope previous;
        final Thread thread = Thread.currentThread();
        NativeMeshEdgeTable table;
        int size;
        boolean closed;

        Scope(Object mesh, List<?> list, Access access, AutoCloseable definition, NativeMeshEdgeTable table) {
            this.mesh = mesh;
            this.list = list;
            this.access = access;
            this.definition = definition;
            this.table = table;
            previous = CURRENT.get();
            size = list.size();
        }

        boolean register(int slot) throws Throwable {
            Object edge = list.get(slot);
            return edge != null
                    && edge.getClass() == access.edge()
                    && table.putFirst(
                            (int) access.first().invokeExact(edge),
                            (int) access.second().invokeExact(edge),
                            slot);
        }

        void discard() {
            if (table != null) table.close();
            table = null;
        }

        @Override
        public void close() {
            if (closed) return;
            if (thread != Thread.currentThread() || CURRENT.get() != this)
                throw new IllegalStateException("native mesh scope close order/thread");
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            try {
                discard();
            } finally {
                LazyTriangulationEdgeBridge.leave(definition);
            }
        }
    }

    /** Only the woven native owner may enter; other callers and unavailable plans preserve native lookup. */
    public static AutoCloseable enter(Object mesh) {
        if (mesh == null || !MESH.equals(mesh.getClass().getName()) || CALLERS.getCallerClass() != mesh.getClass())
            return null;
        AutoCloseable definition = LazyTriangulationEdgeBridge.enterMesh(mesh.getClass());
        if (definition == null) return null;
        return begin(mesh, definition);
    }

    // Package access is for admitted entry and owned differential controls only.
    // The supplied definition lease is consumed on success AND refusal.
    static AutoCloseable begin(Object mesh, AutoCloseable definition) {
        NativeMeshEdgeTable table = null;
        boolean transferred = false;
        try {
            if (definition == null || mesh == null) return null;
            Access access = ACCESS.get(mesh.getClass());
            Object cached = (Object) access.indices().invokeExact(mesh),
                    raw = (Object) access.edges().invokeExact(mesh);
            if (!(cached instanceof int[] indices)
                    || indices.length == 0
                    || indices.length % 3 != 0
                    || raw == null
                    || raw.getClass() != access.list()
                    || !(raw instanceof List<?> list)) return null;
            // Equal endpoints take the native logger path, outside callback-free admission.
            for (int i = 0; i < indices.length; i += 3) {
                if (indices[i] == indices[i + 1] || indices[i + 1] == indices[i + 2] || indices[i + 2] == indices[i])
                    return null;
            }
            int size = list.size();
            if (size > NativeMeshEdgeTable.MAX_ENTRIES) return null;
            int limit = (int) Math.min(NativeMeshEdgeTable.MAX_ENTRIES, (long) size + indices.length);
            table = NativeMeshEdgeTable.reserve(limit);
            if (table == null) return null;
            Scope scope = new Scope(mesh, list, access, definition, table);
            for (int i = 0; i < size; i++) {
                if (!scope.register(i)) return null;
            }
            if (list.size() != size) return null;
            CURRENT.set(scope);
            transferred = true;
            return scope;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        } finally {
            if (!transferred) {
                try {
                    if (table != null) table.close();
                } finally {
                    LazyTriangulationEdgeBridge.leave(definition);
                }
            }
        }
    }

    /** UNKNOWN requires the original invokespecial lookup; ABSENT converts to native null. */
    public static int find(Object mesh, int first, int second, boolean filtered) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.table == null || scope.mesh != mesh || filtered) return NativeMeshEdgeTable.UNKNOWN;
        try {
            if ((Object) scope.access.edges().invokeExact(mesh) != scope.list || scope.list.size() != scope.size) {
                scope.discard();
                return NativeMeshEdgeTable.UNKNOWN;
            }
            return scope.table.find(first, second);
        } catch (Throwable failure) {
            scope.discard();
            FatalErrors.rethrowIfFatal(failure);
            return NativeMeshEdgeTable.UNKNOWN;
        }
    }

    /** Called only after the original successful append. Native typed promotion stays untouched. */
    public static void appended(Object mesh) {
        Scope scope = CURRENT.get();
        if (scope == null || scope.table == null || scope.mesh != mesh) return;
        try {
            if ((Object) scope.access.edges().invokeExact(mesh) != scope.list
                    || scope.list.size() != scope.size + 1
                    || !scope.register(scope.size)) {
                scope.discard();
                return;
            }
            scope.size++;
        } catch (Throwable failure) {
            scope.discard();
            FatalErrors.rethrowIfFatal(failure);
        }
    }
}
