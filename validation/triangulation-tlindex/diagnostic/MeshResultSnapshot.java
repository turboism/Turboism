import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.swing.SwingUtilities;

/** Reads already computed native arrays; never calls updateMesh/getGlIndices or retains host objects. */
final class MeshResultSnapshot {
    private MeshResultSnapshot() {}

    static final class CacheNotReady extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        CacheNotReady(int edgeVersion, int indexCacheVersion, int positionVersion, int vertexCacheVersion) {
            super("native mesh cache is stale: edge=" + edgeVersion + " indexCache=" + indexCacheVersion
                + " position=" + positionVersion + " vertexCache=" + vertexCacheVersion);
        }
    }

    record Result(int pointCount, int positionValues, int indexValues, int edgeVersion,
                  String positionsSha256, String indicesSha256) {}

    static Result capture(Object mesh) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("mesh snapshot requires EDT");
        }
        int edgeVersion = integer(call(mesh, "get_edge_edit_version"));
        int cacheVersion = integer(call(mesh, "getCache_version_gl_indices$core"));
        int positionVersion = integer(call(mesh, "get_postion_edit_version"));
        int vertexCacheVersion = integer(call(mesh, "getCache_version_gl_vertex$core"));
        if (edgeVersion != cacheVersion || positionVersion != vertexCacheVersion) {
            throw new CacheNotReady(edgeVersion, cacheVersion, positionVersion, vertexCacheVersion);
        }
        int count = integer(call(mesh, "getPointCount"));
        Object positions = call(mesh, "getCached_positions$core");
        Object indices = call(mesh, "getCached_indices$core");
        if (!(positions instanceof float[] xy) || !(indices instanceof int[] triangles)) {
            throw new IllegalStateException("native cached arrays unavailable");
        }
        Result result = snapshot(count, edgeVersion, xy, triangles);
        if (edgeVersion != integer(call(mesh, "get_edge_edit_version"))
                || positionVersion != integer(call(mesh, "get_postion_edit_version"))) {
            throw new IllegalStateException("native mesh changed during snapshot");
        }
        return result;
    }

    static Result snapshot(int pointCount, int edgeVersion, float[] positions, int[] indices) {
        if (pointCount < 3 || positions == null || positions.length != (long) pointCount * 2
                || indices == null || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalStateException("invalid native mesh result shape");
        }
        MessageDigest p = digest();
        word(p, positions.length);
        for (float value : positions) {
            if (!Float.isFinite(value)) throw new IllegalStateException("nonfinite native position");
            word(p, Float.floatToRawIntBits(value));
        }
        MessageDigest t = digest();
        word(t, indices.length);
        for (int value : indices) {
            if (value < 0 || value >= pointCount) throw new IllegalStateException("invalid triangle index");
            word(t, value);
        }
        return new Result(pointCount, positions.length, indices.length, edgeVersion,
            HexFormat.of().formatHex(p.digest()), HexFormat.of().formatHex(t.digest()));
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer number)) throw new IllegalStateException("unexpected native integer");
        return number;
    }

    private static Object call(Object receiver, String name) throws Exception {
        if (receiver == null) throw new IllegalStateException("missing native mesh");
        try {
            return receiver.getClass().getMethod(name).invoke(receiver);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("native getter failed", cause);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void word(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
