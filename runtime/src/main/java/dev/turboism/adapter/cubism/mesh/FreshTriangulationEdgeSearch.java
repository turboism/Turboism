package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Runtime guard for fresh-edge searches at the three reviewed, nonescaping h.c() sites. */
public final class FreshTriangulationEdgeSearch {
    private static final String EDGE = "com.live2d.graphics3d.editableMesh.triangulation.j";
    private static final String DIAGNOSTIC_PROPERTY = "turboism.validation.triangulationEdgeGuard";
    private static final String DIAGNOSTIC_TOKEN = "FRESH_EDGE_GUARD_METADATA_V1";

    // ClassValue does not register strong loader keys in a process-wide map. Checking the
    // actual object type avoids class loading or assuming resource bytes equal defined bytes.
    private static final ClassValue<Boolean> IDENTITY_EQUALITY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            if (!EDGE.equals(type.getName())) return result(type, false, "NAME_REJECTED", null);
            if (!Modifier.isFinal(type.getModifiers())) return result(type, false, "FINAL_REJECTED", null);
            if (type.getSuperclass() != Object.class) return result(type, false, "SUPERCLASS_REJECTED", null);
            final Class<?> equalsOwner;
            try {
                equalsOwner = type.getMethod("equals", Object.class).getDeclaringClass();
            } catch (ReflectiveOperationException | SecurityException | LinkageError uncertain) {
                return result(type, false, "METADATA_UNAVAILABLE", null);
            }
            final boolean admitted = equalsOwner == Object.class;
            return result(type, admitted, admitted ? "IDENTITY_EQUALITY" : "EQUALS_OVERRIDE", equalsOwner);
        }
    };

    private FreshTriangulationEdgeSearch() {}

    /**
     * Opt-in validation metadata for a cold ClassValue computation. Concurrent first uses can
     * compute more than once; this is neither a cache-publication receipt nor a query counter.
     * The cache value contains no class or loader reference, and diagnostics cannot change the
     * decision on nonfatal failure. No output or diagnostic metadata is constructed without
     * the exact token; fatal JVM failures retain the shared propagation policy.
     */
    private static boolean result(
            final Class<?> type, final boolean admitted, final String reason, final Class<?> equalsOwner) {
        try {
            if (DIAGNOSTIC_TOKEN.equals(System.getProperty(DIAGNOSTIC_PROPERTY))) {
                final ClassLoader loader = type.getClassLoader();
                System.err.println("TRIANGULATION_FRESH_EDGE_GUARD type=" + type.getName()
                        + " admitted=" + admitted + " reason=" + reason
                        + " equalsOwner=" + (equalsOwner == null ? "unchecked" : equalsOwner.getName())
                        + " loader="
                        + (loader == null
                                ? "bootstrap"
                                : loader.getClass().getName() + "@"
                                        + Integer.toHexString(System.identityHashCode(loader)))
                        + " finalType=" + Modifier.isFinal(type.getModifiers())
                        + " objectSuperclass=" + (type.getSuperclass() == Object.class)
                        + " coldComputation=true");
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable diagnosticFailure) {
            // Keep this bridge JDK-only and contain every nonfatal diagnostic failure.
        }
        return admitted;
    }

    /**
     * Only for a freshly constructed edge before its first insertion in the reviewed local
     * ArrayList. This is NOT a general contains replacement: freshness is proved by the
     * pinned caller, while the actual loaded equality implementation is checked here.
     */
    public static boolean containsFresh(final ArrayList<?> list, final Object edge) {
        if (list != null
                && list.getClass() == ArrayList.class
                && edge != null
                && IDENTITY_EQUALITY.get(edge.getClass())) return false;
        return list.contains(edge);
    }
}
