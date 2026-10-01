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
    private static final ReferenceQueue<ClassLoader> RELEASED = new ReferenceQueue<>();
    private static final Map<LoaderKey, Function<Class<?>, TriangulationDefinitionLifecycle.Gate>> PLANS = new HashMap<>();
    // Only publish an empty holder here. Performing capture in computeValue could
    // create competing gates: ClassValue may compute several candidates and keep one.
    private static final ClassValue<Holder> GATES = new ClassValue<>() {
        @Override protected Holder computeValue(Class<?> type) { return new Holder(); }
    };

    private LazyTriangulationEdgeBridge() {}

    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;
        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) {
            super(loader, queue); hash = System.identityHashCode(loader);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof LoaderKey key)) return false;
            ClassLoader loader = get(); return loader != null && loader == key.get();
        }
    }

    private static void drain() {
        LoaderKey key;
        while ((key = (LoaderKey) RELEASED.poll()) != null) PLANS.remove(key);
    }

    // Factory values must contain immutable fingerprints/origins and the Agent
    // lifecycle only. Never close over an application Class or its loader.
    static boolean register(ClassLoader loader, Function<Class<?>, TriangulationDefinitionLifecycle.Gate> factory) {
        if (loader == null || factory == null) return false;
        synchronized (PLANS) {
            drain(); LoaderKey key = new LoaderKey(loader, RELEASED);
            if (PLANS.containsKey(key)) return false;
            PLANS.put(key, factory); return true;
        }
    }

    private static Function<Class<?>, TriangulationDefinitionLifecycle.Gate> plan(ClassLoader loader) {
        synchronized (PLANS) { drain(); return PLANS.get(new LoaderKey(loader, null)); }
    }

    private static final class Holder {
        private volatile boolean attempted;
        private TriangulationDefinitionLifecycle.Gate gate;
        TriangulationDefinitionLifecycle.Gate gate(Class<?> owner) {
            if (!attempted) synchronized (this) {
                if (!attempted) try {
                    Function<Class<?>, TriangulationDefinitionLifecycle.Gate> factory = plan(owner.getClassLoader());
                    if (factory != null) gate = factory.apply(owner);
                } finally { attempted = true; }
            }
            return gate;
        }
    }

    /** Acquire a thread-confined operation lease, or null for the original native path. */
    public static AutoCloseable enter(Class<?> owner) {
        if (owner == null || !HOST.equals(owner.getName()) || TriangulationDefinitionLifecycle.inTransformerCallback()) return null;
        try {
            TriangulationDefinitionLifecycle.Gate gate = GATES.get(owner).gate(owner);
            return gate == null ? null : gate.acquire();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Release the method's lease; the woven finally path also calls this on failure. */
    public static void leave(AutoCloseable lease) {
        if (lease == null) return;
        try { lease.close(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("definition lease close failed", failure); }
    }
}
