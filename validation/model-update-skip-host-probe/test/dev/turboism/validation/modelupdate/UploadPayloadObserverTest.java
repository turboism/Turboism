package dev.turboism.validation.modelupdate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Standalone tests for the real diagnostic comparison; no GL driver or editor. */
public final class UploadPayloadObserverTest {
    private static final Object CONTEXT = new Object();

    public static void main(String[] args) {
        fullRangeRawBits();
        viewBoundsAndOrder();
        resourceBoundaries();
        boundedMirrors();
        System.out.println("UploadPayloadObserverTest PASS (raw bits, ranges, views, resources, bounds, release)");
    }

    private static boolean upload(UploadPayloadObserver observer, Object context,
                                  int name, long offset, long bytes, Object payload) {
        observer.before(context, "glBindBuffer", new Object[]{34962, name});
        boolean same = observer.before(context, "glBufferSubData", new Object[]{34962, offset, bytes, payload});
        observer.completed(same, bytes, 1L);
        return same;
    }

    private static void fullRangeRawBits() {
        UploadPayloadObserver observer = new UploadPayloadObserver();
        observer.start();
        FloatBuffer floats = FloatBuffer.wrap(new float[129]);
        check(!upload(observer, CONTEXT, 1, 0, 516, floats), "first payload has no baseline");
        check(upload(observer, CONTEXT, 1, 0, 516, floats), "complete equality");
        floats.put(89, 2f);
        check(!upload(observer, CONTEXT, 1, 0, 516, floats), "middle update beyond prefix must be seen");
        floats.put(128, -0f);
        check(!upload(observer, CONTEXT, 1, 0, 516, floats), "positive and negative zero are different bits");
        floats.put(128, Float.intBitsToFloat(0x7fc00001));
        check(!upload(observer, CONTEXT, 1, 0, 516, floats), "NaN replaces zero");
        check(upload(observer, CONTEXT, 1, 0, 516, floats), "identical NaN payload matches");
        floats.put(128, Float.intBitsToFloat(0x7fc00002));
        check(!upload(observer, CONTEXT, 1, 0, 516, floats), "different NaN payloads must not be canonicalized");
        IntBuffer ints = IntBuffer.wrap(new int[]{1, 2, 3});
        check(!upload(observer, CONTEXT, 2, 0, 12, ints), "integer baseline");
        check(upload(observer, CONTEXT, 2, 0, 12, ints), "integer duplicate");
        ints.put(2, 4);
        check(!upload(observer, CONTEXT, 2, 0, 12, ints), "integer tail change");
        observer.stop();
    }

    private static void viewBoundsAndOrder() {
        UploadPayloadObserver observer = new UploadPayloadObserver();
        observer.start();
        FloatBuffer writable = ByteBuffer.allocateDirect(24).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        writable.put(0, 99f).put(1, 1f).put(2, 2f).put(3, 3f).put(4, 77f);
        FloatBuffer view = writable.asReadOnlyBuffer();
        view.position(1).limit(4);
        view.mark();
        check(!upload(observer, CONTEXT, 1, 4, 12, view), "direct read-only slice baseline");
        check(upload(observer, CONTEXT, 1, 4, 12, view), "non-zero CPU position and GPU offset");
        view.reset();
        check(view.position() == 1 && view.limit() == 4, "position, mark and limit preserved");
        writable.put(4, 55f);
        check(upload(observer, CONTEXT, 1, 4, 12, view), "outside requested range must not affect equality");
        writable.put(3, 4f);
        check(!upload(observer, CONTEXT, 1, 4, 12, view), "end of requested range included");
        check(!upload(observer, CONTEXT, 1, 8, 12, view), "different GPU offset gets a new baseline");
        check(!upload(observer, CONTEXT, 1, 8, 16, view), "no reading beyond limit");
        check(!upload(observer, CONTEXT, 1, 8, 12, view), "invalid payload must discard older baseline");
        FloatBuffer oppositeOrder = ByteBuffer.allocateDirect(12).order(ByteOrder.BIG_ENDIAN).asFloatBuffer();
        oppositeOrder.put(0, 1f).put(1, 2f).put(2, 4f);
        check(!upload(observer, CONTEXT, 1, 8, 12, oppositeOrder), "byte order is part of the payload representation");
        observer.stop();
    }

    private static void resourceBoundaries() {
        UploadPayloadObserver observer = new UploadPayloadObserver();
        observer.start();
        IntBuffer data = IntBuffer.wrap(new int[]{3});
        check(!upload(observer, CONTEXT, 5, 0, 4, data), "initial resource");
        observer.before(CONTEXT, "glDeleteBuffers", new Object[]{1, new int[]{5}, 0});
        check(!upload(observer, CONTEXT, 5, 0, 4, data), "reused GL name must not reuse baseline");
        observer.before(CONTEXT, "glBufferData", new Object[]{34962, 4L, data, 35048});
        check(!upload(observer, CONTEXT, 5, 0, 4, data), "reallocated storage baseline invalidated");
        observer.before(CONTEXT, "glMapBuffer", new Object[]{34962, 35002});
        check(!upload(observer, CONTEXT, 5, 0, 4, data), "mapped write not assumed absent");
        observer.nativeFailure();
        check(!upload(observer, CONTEXT, 5, 0, 4, data), "failure invalidation");
        check(!upload(observer, new Object(), 5, 0, 4, data), "context change invalidation");
        check(!upload(observer, null, 5, 0, 4, data), "unknown context not comparable");
        check(!upload(observer, CONTEXT, 5, Long.MAX_VALUE - 3L, 4, data), "overflowing range not comparable");
        check(!upload(observer, CONTEXT, 5, 0, 4, ByteBuffer.allocate(4)), "unsupported raw byte representation is not guessed");
        observer.stop();
    }

    private static void boundedMirrors() {
        UploadPayloadObserver observer = new UploadPayloadObserver(16L, 2);
        observer.start();
        IntBuffer data = IntBuffer.wrap(new int[]{1, 2});
        check(!upload(observer, CONTEXT, 1, 0, 8, data), "resource one");
        check(!upload(observer, CONTEXT, 2, 0, 8, data), "resource two");
        check(!upload(observer, CONTEXT, 3, 0, 8, data), "bounded eviction");
        check(!upload(observer, CONTEXT, 1, 0, 8, data), "evicted baseline cannot match");
        check(observer.report().contains("uploadPayload.peakRetainedBytes=16\n"), "payload bound respected");
        check(observer.report().contains("uploadPayload.evictions=2\n"), "evictions counted");
        check(!upload(observer, CONTEXT, 4, 0, 20, IntBuffer.wrap(new int[5])), "over-budget payload not retained");
        observer.stop();
        check(observer.report().contains("uploadPayload.retainedBytes=0\n"), "stop releases payload storage");
        observer.start();
        check(observer.report().contains("uploadPayload.observedCalls=0\n"), "fresh window resets counts");
        observer.stop();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
