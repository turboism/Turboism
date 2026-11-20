package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.core.io.Sha256;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Serializes deformed drawable geometry and buffer primitives into SHA-256 frame digests. */
public final class DrawableFormDigest {

    private static final int BUFFER_BYTES = 8192;

    private final Class<?> meshFormType;
    private final Class<?> pathFormType;
    private final MethodHandle meshPositions;
    private final MethodHandle pathPositions;
    private final MethodHandle pointCurve;
    private final MethodHandle pointWidth;
    private final MethodHandle pointOpacity;
    private final MethodHandle spPoint;
    private final MethodHandle spStart;
    private final MethodHandle spEnd;
    private final MethodHandle vecX;
    private final MethodHandle vecY;

    /** Binds form-geometry handles resolved once against the admitted host classloader. */
    public DrawableFormDigest(
            final Class<?> meshFormType,
            final Class<?> pathFormType,
            final MethodHandle meshPositions,
            final MethodHandle pathPositions,
            final MethodHandle pointCurve,
            final MethodHandle pointWidth,
            final MethodHandle pointOpacity,
            final MethodHandle spPoint,
            final MethodHandle spStart,
            final MethodHandle spEnd,
            final MethodHandle vecX,
            final MethodHandle vecY) {
        this.meshFormType = Objects.requireNonNull(meshFormType, "meshFormType");
        this.pathFormType = Objects.requireNonNull(pathFormType, "pathFormType");
        this.meshPositions = Objects.requireNonNull(meshPositions, "meshPositions");
        this.pathPositions = Objects.requireNonNull(pathPositions, "pathPositions");
        this.pointCurve = Objects.requireNonNull(pointCurve, "pointCurve");
        this.pointWidth = Objects.requireNonNull(pointWidth, "pointWidth");
        this.pointOpacity = Objects.requireNonNull(pointOpacity, "pointOpacity");
        this.spPoint = Objects.requireNonNull(spPoint, "spPoint");
        this.spStart = Objects.requireNonNull(spStart, "spStart");
        this.spEnd = Objects.requireNonNull(spEnd, "spEnd");
        this.vecX = Objects.requireNonNull(vecX, "vecX");
        this.vecY = Objects.requireNonNull(vecY, "vecY");
    }

    /** Allocates a little-endian scratch buffer sized for batched digest updates. */
    public static ByteBuffer newBuffer() {
        return ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Flushes remaining buffered bytes into {@code md} and returns the final SHA-256 digest. */
    public static byte[] finish(final MessageDigest md, final ByteBuffer buffer) {
        buffer.flip();
        md.update(buffer);
        return md.digest();
    }

    /** Buffers one 32-bit integer, flushing to {@code md} when scratch space fills. */
    public static void putInt(final MessageDigest md, final ByteBuffer buffer, final int value) {
        if (buffer.remaining() < Integer.BYTES) {
            buffer.flip();
            md.update(buffer);
            buffer.clear();
        }
        buffer.putInt(value);
    }

    /** Buffers raw IEEE-754 bits of {@code value} so NaN payloads remain distinct. */
    public static void putFloat(final MessageDigest md, final ByteBuffer buffer, final float value) {
        putInt(md, buffer, Float.floatToRawIntBits(value));
    }

    /** Buffers one 64-bit integer in two big-endian 32-bit halves. */
    public static void putLong(final MessageDigest md, final ByteBuffer buffer, final long value) {
        putInt(md, buffer, (int) (value >>> 32));
        putInt(md, buffer, (int) value);
    }

    /**
     * Hashes every drawable's deformed form, optional affected form, and draw order in model order.
     *
     * @param model native {@code CModel} instance
     * @param getAllDrawables handle returning the model's drawable list
     * @param getDeformedForm handle returning a drawable's deformed form
     * @param getAffectedForm optional handle returning a drawable's glue-affected form, or {@code null}
     * @param getDrawOrder handle returning a drawable's integer draw order
     * @return 32-byte SHA-256 digest of all drawable states
     */
    public byte[] digestDrawables(
            final Object model,
            final MethodHandle getAllDrawables,
            final MethodHandle getDeformedForm,
            final MethodHandle getAffectedForm,
            final MethodHandle getDrawOrder)
            throws Throwable {
        final MessageDigest md = Sha256.digest();
        final Object drawables = (Object) getAllDrawables.invokeExact(model);
        if (!(drawables instanceof List)) {
            throw new IllegalStateException("drawables absent");
        }
        final ByteBuffer buffer = newBuffer();
        for (final Object drawable : (List<?>) drawables) {
            if (drawable == null) {
                throw new IllegalStateException("null drawable");
            }
            putForm(md, buffer, (Object) getDeformedForm.invokeExact(drawable));
            if (getAffectedForm != null) {
                putForm(md, buffer, (Object) getAffectedForm.invokeExact(drawable));
            }
            putInt(md, buffer, (int) getDrawOrder.invokeExact(drawable));
        }
        return finish(md, buffer);
    }

    /** Serializes one mesh or path form; {@code null} writes a distinct absent tag. */
    public void putForm(final MessageDigest md, final ByteBuffer buffer, final Object form) throws Throwable {
        if (form == null) {
            putInt(md, buffer, 0);
        } else if (meshFormType.isInstance(form)) {
            final float[] positions = (float[]) (Object) meshPositions.invokeExact(form);
            putInt(md, buffer, 1);
            if (positions == null) {
                putInt(md, buffer, -1);
            } else {
                putInt(md, buffer, positions.length);
                for (final float position : positions) {
                    putFloat(md, buffer, position);
                }
            }
        } else if (pathFormType.isInstance(form)) {
            final Object points = (Object) pathPositions.invokeExact(form);
            putInt(md, buffer, 2);
            if (points == null) {
                putInt(md, buffer, -1);
            } else if (!(points instanceof List)) {
                throw new IllegalStateException(
                        "path points of unexpected type " + points.getClass().getName());
            } else {
                putInt(md, buffer, ((List<?>) points).size());
                for (final Object point : (List<?>) points) {
                    if (point == null) {
                        putInt(md, buffer, -1);
                        continue;
                    }
                    putInt(md, buffer, 1);
                    final Object curve = (Object) pointCurve.invokeExact(point);
                    if (curve == null) {
                        putInt(md, buffer, -1);
                    } else {
                        putVector(md, buffer, (Object) spPoint.invokeExact(curve));
                        putVector(md, buffer, (Object) spStart.invokeExact(curve));
                        putVector(md, buffer, (Object) spEnd.invokeExact(curve));
                    }
                    putFloat(md, buffer, (float) pointWidth.invokeExact(point));
                    putFloat(md, buffer, (float) pointOpacity.invokeExact(point));
                }
            }
        } else {
            throw new IllegalStateException(
                    "undigestable form " + form.getClass().getName());
        }
    }

    /** Serializes a 2D vector, writing {@code -1} when absent. */
    public void putVector(final MessageDigest md, final ByteBuffer buffer, final Object vector) throws Throwable {
        if (vector == null) {
            putInt(md, buffer, -1);
            return;
        }
        putInt(md, buffer, 1);
        putFloat(md, buffer, (float) vecX.invokeExact(vector));
        putFloat(md, buffer, (float) vecY.invokeExact(vector));
    }
}
