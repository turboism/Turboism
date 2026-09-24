package dev.turboism.validation.modelupdate;

import java.nio.FloatBuffer;

/** Real cache logic; no GL context or editor process is created. */
public final class UniformValueCacheTest {
    public static void main(String[] args) throws Exception {
        Object context = new Object();
        UniformValueCache cache = new UniformValueCache(2);
        Object[] value = {3, 0.5f};
        cache.begin(context);
        check(!cache.before(context, "glUniform1f", value), "unknown program stays native");
        cache.before(context, "glUseProgram", new Object[]{7});
        check(!cache.before(context, "glUniform1f", value), "first write stays native");
        check(!cache.before(context, "glUniform1f", value), "unconfirmed write stays native");
        cache.checkedError(0);
        check(cache.before(context, "glUniform1f", value), "confirmed exact duplicate skipped");
        check(!cache.before(context, "glUniform1f", new Object[]{3, -0.0f}), "change invalidates previous value");
        cache.checkedError(0);
        check(!cache.before(context, "glUniform1f", new Object[]{3, +0.0f}), "signed zeros differ");
        cache.checkedError(0);
        cache.before(context, "glUseProgram", new Object[]{8});
        check(!cache.before(context, "glUniform1f", new Object[]{3, +0.0f}), "program identity differs");
        cache.checkedError(0);
        check(cache.before(context, "glUniform1f", new Object[]{3, +0.0f}), "second program confirmed");
        cache.before(context, "glLinkProgram", new Object[]{8});
        check(!cache.before(context, "glUniform1f", new Object[]{3, +0.0f}), "relink invalidates");
        cache.checkedError(1282);
        check(!cache.before(context, "glUniform1f", new Object[]{3, +0.0f}), "errors revoke program trust");
        cache.end();
        check(cache.retained() == 0, "frame end releases data");
        cache.begin(context);
        cache.before(context, "glUseProgram", new Object[]{7}); cache.checkedError(0);
        float[] matrix = new float[20]; matrix[3] = 1f;
        FloatBuffer buffer = FloatBuffer.wrap(matrix); buffer.position(2); buffer.limit(18);
        Object[] m = {5, 1, false, buffer};
        check(!cache.before(context, "glUniformMatrix4fv", m), "matrix native first");
        cache.checkedError(0);
        check(cache.before(context, "glUniformMatrix4fv", m), "full matrix duplicate");
        check(buffer.position() == 2 && buffer.limit() == 18, "caller buffer view preserved");
        matrix[17] = 2f;
        check(!cache.before(context, "glUniformMatrix4fv", m), "tail matrix change detected");
        cache.checkedError(0);
        check(!cache.before(context, "glUniformMatrix4fv", new Object[]{5, 1, true, matrix, 2}), "transpose is part of identity");
        cache.checkedError(0);
        check(cache.before(context, "glUniformMatrix4fv", new Object[]{5, 1, true, matrix, 2}), "array view equivalent");
        cache.before(context, "glUniformMatrix4fv", new Object[]{5, 2, false, matrix, 0});
        check(!cache.before(context, "glUniformMatrix4fv", new Object[]{5, 1, true, matrix, 2}), "unsupported overlapping array write invalidates");
        cache.checkedError(0);
        check(!cache.before(context, "glUniform1i", new Object[]{-1, 1}), "ignored locations stay native");
        check(!cache.before(context, "glUniform1i", new Object[]{6, 1}), "new integer location");
        cache.checkedError(0);
        check(cache.retained() <= 2, "bounded storage");
        check(!cache.before(new Object(), "glUniform1i", new Object[]{6, 1}), "context transition fails open");
        cache.begin(context); cache.before(context, "glUseProgram", new Object[]{7}); cache.checkedError(0);
        cache.before(context, "glUniform1f", value); cache.checkedError(0);
        Thread foreign = new Thread(() -> check(!cache.before(context, "glUniform1f", value), "thread transition fails open"));
        foreign.start(); foreign.join();
        check(cache.retained() == 0, "foreign access retires frame");
        System.out.println("UniformValueCacheTest PASS (confirmation, raw values, matrices, programs, invalidation, ownership, bounds)");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
