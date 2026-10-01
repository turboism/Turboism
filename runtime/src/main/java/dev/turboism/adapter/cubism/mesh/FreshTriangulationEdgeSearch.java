package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Runtime guard for fresh-edge searches at the three reviewed, nonescaping h.c() sites. */
public final class FreshTriangulationEdgeSearch {
    private static final String EDGE = "com.live2d.graphics3d.editableMesh.triangulation.j";

    // ClassValue does not register strong loader keys in a process-wide map. Checking the
    // actual object type avoids class loading or assuming resource bytes equal defined bytes.
    private static final ClassValue<Boolean> IDENTITY_EQUALITY = new ClassValue<>() {
        @Override protected Boolean computeValue(final Class<?> type) {
            if (!EDGE.equals(type.getName()) || !Modifier.isFinal(type.getModifiers())
                    || type.getSuperclass() != Object.class) return false;
            try {
                return type.getMethod("equals", Object.class).getDeclaringClass() == Object.class;
            } catch (ReflectiveOperationException | SecurityException | LinkageError uncertain) {
                return false;
            }
        }
    };

    private FreshTriangulationEdgeSearch() {}

    /**
     * Only for a freshly constructed edge before its first insertion in the reviewed local
     * ArrayList. This is NOT a general contains replacement: freshness is proved by the
     * pinned caller, while the actual loaded equality implementation is checked here.
     */
    public static boolean containsFresh(final ArrayList<?> list, final Object edge) {
        if (list != null && list.getClass() == ArrayList.class && edge != null
                && IDENTITY_EQUALITY.get(edge.getClass())) return false;
        return list.contains(edge);
    }
}
