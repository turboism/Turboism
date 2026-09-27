package dev.turboism.validation.modelupdate;

/** Standalone tests for the single-display, error-confirmed location cache. */
public final class UniformLocationCacheTest {
    public static void main(String[] args) throws Exception {
        Object context = new Object();
        UniformLocationCache cache = new UniformLocationCache(4);
        cache.begin(context);
        cache.queried(context, 7, "matrix", 3);
        check(cache.lookup(context, 7, "matrix") == null, "unconfirmed return must not be reused");
        cache.checkedError(0);
        check(Integer.valueOf(3).equals(cache.lookup(context, 7, "matrix")), "confirmed location reused");
        check(cache.lookup(context, 8, "matrix") == null, "program identity");
        check(cache.lookup(context, 7, "color") == null, "uniform identity");
        cache.queried(context, 7, "absent", -1);
        cache.checkedError(0);
        check(Integer.valueOf(-1).equals(cache.lookup(context, 7, "absent")), "valid absent uniforms cache -1");
        cache.invalidate(7);
        check(cache.lookup(context, 7, "matrix") == null, "relink/delete removes previous generation");
        cache.queried(context, 7, "matrix", 9);
        cache.checkedError(1282);
        cache.checkedError(0);
        check(cache.lookup(context, 7, "matrix") == null, "error cannot create a valid baseline");
        cache.queried(context, 7, "matrix", 11);
        cache.checkedError(0);
        cache.end();
        check(cache.lookup(context, 7, "matrix") == null, "out-of-display query stays native");
        cache.begin(context);
        check(cache.lookup(context, 7, "matrix") == null, "no reuse across frames");
        cache.queried(context, 7, "matrix", 13);
        cache.checkedError(0);
        check(cache.lookup(new Object(), 7, "matrix") == null, "context transition rejects old cache");
        cache.begin(context);
        cache.queried(context, 7, "matrix", 15);
        cache.checkedError(0);
        Thread other = new Thread(() -> check(cache.lookup(context, 7, "matrix") == null, "no cross-thread reuse"));
        other.start(); other.join();
        check(cache.lookup(context, 7, "matrix") == null, "foreign access invalidates ownership");
        cache.begin(context);
        for (int i = 0; i < 20; i++) { cache.queried(context, i + 1, "x", i); cache.checkedError(0); }
        check(cache.retained() <= 4, "entry bound");
        cache.fault();
        check(cache.retained() == 0, "failure releases references");
        cache.begin(context);
        cache.queried(context, 0, "x", -1);
        cache.queried(context, 7, null, -1);
        cache.checkedError(0);
        check(cache.retained() == 0, "invalid inputs never admitted");
        cache.end();
        System.out.println("UniformLocationCacheTest PASS (error confirmation, generations, frame, context, thread, bounds)");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
