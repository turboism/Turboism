package dev.turboism.adapter.cubism.mesh;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JDK-only entry/exit bridge for the reviewed lazy-edge method. Missing or rejected
 * plans return null and preserve its original eager construction path. A lease
 * covers the entire method, including its exceptional exit.
 */
public final class LazyTriangulationEdgeBridge {
    private static final String HOST = "com.live2d.graphics3d.editableMesh.triangulation.h";
    private static final String MESH = "com.live2d.graphics3d.editableMesh.GEditableMesh2";
    private static final ReferenceQueue<ClassLoader> RELEASED = new ReferenceQueue<>();
    private static final Map<LoaderKey, Function<Class<?>, Admission>> PLANS = new HashMap<>();
    private static final Map<LoaderKey, String> MESH_PREPARED = new HashMap<>();
    private static final Map<LoaderKey, String> POINT_PREPARED = new HashMap<>();
    // Only publish an empty holder here. Performing capture in computeValue could
    // create competing gates: ClassValue may compute several candidates and keep one.
    private static final ClassValue<Holder> GATES = new ClassValue<>() {
        @Override
        protected Holder computeValue(Class<?> type) {
            return new Holder();
        }
    };

    // Cache the shared admission, never an application Class or loader. Repeated
    // point queries must not resolve h twice or create a second capture.
    private static final ClassValue<PointHolder> POINT_GATES = new ClassValue<>() {
        @Override
        protected PointHolder computeValue(Class<?> type) {
            return new PointHolder();
        }
    };

    private static final class PointHolder {
        private volatile boolean attempted;
        private Admission admission;

        Admission admission(Class<?> owner) throws ClassNotFoundException {
            if (!attempted)
                synchronized (this) {
                    if (!attempted)
                        try {
                            ClassLoader loader = owner.getClassLoader();
                            Class<?> host = Class.forName(HOST, false, loader);
                            if (host.getClassLoader() == loader
                                    && Class.forName(owner.getName(), false, loader) == owner)
                                admission = GATES.get(host).admission(host);
                        } finally {
                            attempted = true;
                        }
                }
            return admission;
        }
    }

    private LazyTriangulationEdgeBridge() {}

    /** One capture result; mesh permission belongs to that exact captured dependency set. */
    record Admission(TriangulationDefinitionLifecycle.Gate gate, boolean meshIncluded, boolean pointIncluded) {
        Admission(TriangulationDefinitionLifecycle.Gate gate, boolean meshIncluded) {
            this(gate, meshIncluded, false);
        }
    }

    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;

        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) {
            super(loader, queue);
            hash = System.identityHashCode(loader);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof LoaderKey key)) return false;
            ClassLoader loader = get();
            return loader != null && loader == key.get();
        }
    }

    private static void drain() {
        LoaderKey key;
        while ((key = (LoaderKey) RELEASED.poll()) != null) {
            PLANS.remove(key);
            MESH_PREPARED.remove(key);
            POINT_PREPARED.remove(key);
        }
    }

    static void meshPrepared(ClassLoader loader, String fingerprint) {
        if (loader == null || fingerprint == null) return;
        synchronized (PLANS) {
            drain();
            MESH_PREPARED.put(new LoaderKey(loader, RELEASED), fingerprint);
        }
    }

    static void pointPrepared(ClassLoader loader, String fingerprint) {
        if (loader == null || fingerprint == null) return;
        synchronized (PLANS) {
            drain();
            POINT_PREPARED.put(new LoaderKey(loader, RELEASED), fingerprint);
        }
    }

    static boolean pointPreparedMatches(ClassLoader loader, String expected) {
        synchronized (PLANS) {
            drain();
            return expected != null && expected.equals(POINT_PREPARED.get(new LoaderKey(loader, null)));
        }
    }

    static boolean meshPreparedMatches(ClassLoader loader, String expected) {
        synchronized (PLANS) {
            drain();
            return expected != null && expected.equals(MESH_PREPARED.get(new LoaderKey(loader, null)));
        }
    }

    // Factory values must contain immutable fingerprints/origins and the Agent
    // lifecycle only. Never close over an application Class or its loader.
    static boolean register(ClassLoader loader, Function<Class<?>, TriangulationDefinitionLifecycle.Gate> factory) {
        if (factory == null) return false;
        return registerShared(loader, owner -> new Admission(factory.apply(owner), false));
    }

    // The factory may return the original non-mesh admission when extended preparation declines.
    // Only an extended capture may set meshIncluded; a loader-wide Boolean is insufficient.
    static boolean registerShared(ClassLoader loader, Function<Class<?>, Admission> factory) {
        if (loader == null || factory == null) return false;
        synchronized (PLANS) {
            drain();
            LoaderKey key = new LoaderKey(loader, RELEASED);
            if (PLANS.containsKey(key)) return false;
            PLANS.put(key, factory);
            return true;
        }
    }

    private static Function<Class<?>, Admission> plan(ClassLoader loader) {
        synchronized (PLANS) {
            drain();
            return PLANS.get(new LoaderKey(loader, null));
        }
    }

    private static final class Holder {
        private volatile boolean attempted;
        private Admission admission;

        Admission admission(Class<?> owner) {
            if (!attempted)
                synchronized (this) {
                    if (!attempted)
                        try {
                            Function<Class<?>, Admission> factory = plan(owner.getClassLoader());
                            if (factory != null) admission = factory.apply(owner);
                        } finally {
                            attempted = true;
                        }
                }
            return admission;
        }
    }

    /** Acquire a thread-confined operation lease, or null for the original native path. */
    public static AutoCloseable enter(Class<?> owner) {
        if (owner == null || !HOST.equals(owner.getName()) || TriangulationDefinitionLifecycle.inTransformerCallback())
            return null;
        try {
            Admission admission = GATES.get(owner).admission(owner);
            return admission == null
                            || admission.gate() == null
                            || !admission.gate().covers(owner)
                    ? null
                    : admission.gate().acquire();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /**
     * Acquire the same captured dependency lease for a local TriangleList builder.
     * Resolving the operation owner here keeps missing or invalid host dependencies
     * inside the guarded fallback rather than a woven class-literal linkage failure.
     */
    public static AutoCloseable enterBuilder(Class<?> owner) {
        if (owner == null
                || !owner.getName().equals(TriangulationEdgeIndexTransformer.TARGET_CLASS_NAME)
                || owner.getClassLoader() == null
                || TriangulationDefinitionLifecycle.inTransformerCallback()) return null;
        try {
            return enter(Class.forName(HOST, false, owner.getClassLoader()));
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /**
     * Acquire the same captured gate for the native mesh suffix. The factory must have
     * included the woven mesh and its dependencies; legacy plans always preserve native
     * mesh lookup. Parent delegation cannot substitute another loader's captured owner.
     */
    public static AutoCloseable enterMesh(Class<?> owner) {
        if (owner == null
                || !MESH.equals(owner.getName())
                || owner.getClassLoader() == null
                || owner.getModule().isNamed()
                || TriangulationDefinitionLifecycle.inTransformerCallback()) return null;
        try {
            ClassLoader loader = owner.getClassLoader();
            Class<?> host = Class.forName(HOST, false, loader);
            if (host.getClassLoader() != loader || Class.forName(MESH, false, loader) != owner) return null;
            Admission admission = GATES.get(host).admission(host);
            return admission == null
                            || !admission.meshIncluded()
                            || admission.gate() == null
                            || !admission.gate().covers(host)
                            || !admission.gate().covers(owner)
                    ? null
                    : admission.gate().acquire();
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Share h's exact captured gate; legacy/declined plans preserve the original point method. */
    public static AutoCloseable enterPoint(Class<?> owner) {
        if (owner == null
                || !PointTriangleReusePreparation.OWNER.equals(owner.getName())
                || owner.getClassLoader() == null
                || owner.getModule().isNamed()
                || TriangulationDefinitionLifecycle.inTransformerCallback()) return null;
        try {
            Admission admission = POINT_GATES.get(owner).admission(owner);
            return admission == null
                            || !admission.pointIncluded()
                            || admission.gate() == null
                            || !admission.gate().covers(owner)
                    ? null
                    : admission.gate().acquire();
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Release the method's lease; the woven finally path also calls this on failure. */
    public static void leave(AutoCloseable lease) {
        if (lease == null) return;
        try {
            lease.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("definition lease close failed", failure);
        }
    }
}
