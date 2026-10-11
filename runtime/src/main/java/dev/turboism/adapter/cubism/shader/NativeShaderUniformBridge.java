package dev.turboism.adapter.cubism.shader;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal entrypoint for the verified shader uniform-location reuse hook. Host code calls
 * {@link #lookup} instead of a repeated {@code glGetUniformLocation} on every draw; the official
 * query stays the fallback for every ineligible or disabled call. The bridge holds no host type
 * dependency: GL, context and shader members are reached reflectively and cached per class.
 */
public final class NativeShaderUniformBridge {
    private static volatile boolean enabled;
    private static volatile boolean patched;
    private static final WeakHashMap<Object, State> CACHE = new WeakHashMap<>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong QUERIES = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();
    static final int MAX_SHADERS = 1024;
    static final int MAX_NAMES = 4096;

    private NativeShaderUniformBridge() { }

    /** Installer lifecycle switch. Disabling leaves transformed calls on the official path. */
    public static void enabled(boolean value) { enabled = value; }

    /**
     * Returns whether verified uniform-location reuse is active.
     *
     * @return {@code true} while an admitted installation owns the hook
     */
    public static boolean enabled() { return enabled; }

    /**
     * Returns whether the exact reviewed host shader definition was transformed.
     *
     * @return {@code true} once the transformer emitted the patched first definition
     */
    public static boolean patched() { return patched; }

    /**
     * @return total cache hits served without a native uniform query
     */
    public static long hits() { return HITS.get(); }

    /**
     * @return total calls that reached the official {@code glGetUniformLocation}
     */
    public static long queries() { return QUERIES.get(); }

    /**
     * @return total shader lifecycle invalidations observed
     */
    public static long invalidations() { return INVALIDATIONS.get(); }

    static void patched(boolean value) { patched = value; }

    /**
     * Resolves a uniform location, reusing the recorded location while the same shader, GL
     * context identity and handle, and program stay current. Every ineligible call (disabled,
     * missing or empty host uniform table, mismatched program, inactive or non-current context)
     * delegates to the official query; the original exception is preserved exactly.
     *
     * @param gl host GL implementation object
     * @param program host program identifier
     * @param name uniform name as passed by the host draw path
     * @param shader host shader instance holding the uniform table
     * @return the uniform location
     */
    public static int lookup(Object gl, int program, String name, Object shader) {
        if (!enabled || program <= 0 || name == null) return original(gl, program, name);
        Object context = null;
        long handle = 0;
        boolean eligible = false;
        try {
            context = invoke(GL.get(gl.getClass())[1], gl);
            if (context != null) {
                var api = CONTEXT.get(context.getClass());
                handle = ((Number) invoke(api[0], context)).longValue();
                var shaderApi = SHADER.get(shader.getClass());
                eligible = handle != 0 && Boolean.TRUE.equals(invoke(api[1], context))
                    && invoke(api[2], null) == context
                    && ((Number) invoke(shaderApi[0], shader)).intValue() == program
                    && invoke(shaderApi[1], shader) instanceof Map<?, ?> uniforms && !uniforms.isEmpty();
            }
        } catch (IllegalStateException rejected) {
            eligible = false;
        }
        if (!eligible) return original(gl, program, name);
        State state;
        synchronized (CACHE) {
            state = CACHE.get(shader);
            if (state == null || !state.same(context, handle, program)) {
                if (CACHE.size() >= MAX_SHADERS) CACHE.clear();
                state = new State(context, handle, program);
                CACHE.put(shader, state);
            }
            Integer location = state.locations.get(name);
            if (location != null) { HITS.incrementAndGet(); return location; }
        }
        int location = original(gl, program, name);
        synchronized (CACHE) {
            if (CACHE.get(shader) == state && state.locations.size() < MAX_NAMES) state.locations.put(name, location);
        }
        return location;
    }

    /**
     * Drops every recorded location for a shader that reinitializes or changes program.
     *
     * @param shader host shader instance
     */
    public static void invalidate(Object shader) {
        synchronized (CACHE) { CACHE.remove(shader); }
        INVALIDATIONS.incrementAndGet();
    }

    private static final class State {
        final WeakReference<Object> context;
        final long handle;
        final int program;
        final Map<String, Integer> locations = new HashMap<>();
        State(Object context, long handle, int program) {
            this.context = new WeakReference<>(context);
            this.handle = handle;
            this.program = program;
        }
        boolean same(Object context, long handle, int program) {
            return this.context.get() == context && this.handle == handle && this.program == program;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static final ClassValue<Method[]> GL = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            return new Method[] {
                method(type, "glGetUniformLocation", int.class, String.class),
                method(type, "getContext")
            };
        }
    };

    private static final ClassValue<Method[]> CONTEXT = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            return new Method[] {
                method(type, "getHandle"),
                method(type, "isCreated"),
                method(type, "getCurrent")
            };
        }
    };

    private static final ClassValue<Method[]> SHADER = new ClassValue<>() {
        @Override protected Method[] computeValue(Class<?> type) {
            return new Method[] {
                method(type, "getGlProgramID"),
                method(type, "getUniformMap")
            };
        }
    };

    private static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static int original(Object gl, int program, String name) {
        QUERIES.incrementAndGet();
        return (int) invoke(GL.get(gl.getClass())[0], gl, program, name);
    }
}
