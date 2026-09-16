package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opt-in, validation-only experiment: memoize confirmed uniform locations within
 * one display, never across frames. Does not skip any draw, buffer write, uniform
 * write or error query. No timings are added on the GL call path. Not a supported
 * production GL decorator: shared-context relinks require separate admission.
 */
final class UniformLocationTrial implements InvocationHandler, AutoCloseable {
    private final Object downstream, pipeline;
    private final Class<?> api;
    private final Method contextGetter;
    private final UniformLocationCache cache = new UniformLocationCache(4096);
    private final boolean shadow;
    private volatile boolean enabled;
    private Component drawable;
    private Method getGl, setGl, removeListener;
    private Object beforeListener, afterListener;
    private volatile boolean capturePending;
    private FrameReadback captured;
    private Throwable captureFailure;
    private long queries, nativeQueries, skippedQueries, shadowQueries, shadowMismatches,
        glErrors, failures, draws, frames;

    UniformLocationTrial(Class<?> api, Object downstream, boolean shadow) throws Exception {
        if (!api.isInterface() || !api.isInstance(downstream)) throw new IllegalArgumentException("invalid GL interface");
        this.downstream = downstream;
        this.api = api;
        this.shadow = shadow;
        contextGetter = api.getMethod("getContext");
        pipeline = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, this);
    }

    static UniformLocationTrial attach(Component drawable, boolean shadow) throws Exception {
        ClassLoader loader = drawable.getClass().getClassLoader();
        Class<?> auto = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        Class<?> gl = Class.forName("com.jogamp.opengl.GL", false, loader);
        Class<?> api = Class.forName("com.jogamp.opengl.GL3", false, loader);
        Class<?> listener = Class.forName("com.jogamp.opengl.GLEventListener", false, loader);
        Method get = auto.getMethod("getGL"), set = auto.getMethod("setGL", gl);
        UniformLocationTrial trial = new UniformLocationTrial(api, get.invoke(drawable), shadow);
        trial.drawable = drawable;
        trial.getGl = get; trial.setGl = set;
        trial.removeListener = auto.getMethod("removeGLEventListener", listener);
        trial.beforeListener = trial.listener(loader, listener, true);
        trial.afterListener = trial.listener(loader, listener, false);
        try {
            set.invoke(drawable, trial.pipeline);
            if (get.invoke(drawable) != trial.pipeline) throw new IllegalStateException("GL trial not installed");
            auto.getMethod("addGLEventListener", int.class, listener).invoke(drawable, 0, trial.beforeListener);
            auto.getMethod("addGLEventListener", listener).invoke(drawable, trial.afterListener);
            return trial;
        } catch (Throwable failure) {
            trial.close();
            throw failure;
        }
    }

    private Object listener(ClassLoader loader, Class<?> api, boolean before) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{api}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (method.getName().equals("display")) {
                if (args == null || args[0] != drawable) { cache.fault(); failures++; return null; }
                if (before) beginFrame(); else endFrame();
            } else if (method.getName().equals("dispose")) endFrame();
            return null;
        });
    }

    void setEnabled(boolean value) { cache.end(); enabled = value; }
    void beginFrame() throws Exception {
        frames++;
        if (enabled) cache.begin(contextGetter.invoke(downstream)); else cache.end();
    }
    void endFrame() { cache.end(); }
    Object wrapped() { return pipeline; }
    void requestReadback() { captured = null; captureFailure = null; capturePending = true; }
    FrameReadback takeReadback() {
        if (captureFailure != null || captured == null) {
            throw new IllegalStateException("native GL frame readback unavailable", captureFailure);
        }
        FrameReadback result = captured;
        captured = null;
        if (result.distinctPixels() < 2) throw new IllegalStateException("uniform/blank framebuffer is not valid parity evidence");
        return result;
    }
    private int packInteger(int key) throws Exception {
        int[] value = new int[1];
        api.getMethod("glGetIntegerv", int.class, int[].class, int.class)
            .invoke(downstream, key, value, 0);
        return value[0];
    }
    private void capture(Object[] args) {
        capturePending = false;
        try {
            if (args == null || args.length != 7 || !(args[6] instanceof java.nio.Buffer buffer)) {
                throw new IllegalStateException("readback uses unsupported overload");
            }
            captured = FrameReadback.capture(buffer, (Integer) args[2], (Integer) args[3],
                (Integer) args[4], (Integer) args[5], packInteger(3330), packInteger(3333),
                packInteger(3332), packInteger(3331));
        } catch (Throwable failure) { captureFailure = failure; }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
        String name = method.getName();
        boolean query = name.equals("glGetUniformLocation") && args != null && args.length == 2
            && args[0] instanceof Integer && (args[1] == null || args[1] instanceof String);
        Object context = null;
        Integer cached = null;
        if (query) {
            queries++;
            if (enabled) {
                context = contextGetter.invoke(downstream);
                cached = cache.lookup(context, (Integer) args[0], (String) args[1]);
                if (cached != null && !shadow) { skippedQueries++; return cached; }
            }
            nativeQueries++;
        }
        if (name.equals("glLinkProgram") || name.equals("glProgramBinary") || name.equals("glDeleteProgram")) {
            if (args != null && args.length > 0 && args[0] instanceof Integer id) cache.invalidate(id);
            else cache.fault();
        }
        try {
            Object result = method.invoke(downstream, args);
            if (capturePending && name.equals("glReadPixels")) capture(args);
            if (query && enabled && result instanceof Integer location) {
                if (cached != null) {
                    shadowQueries++;
                    if (!cached.equals(location)) { shadowMismatches++; cache.fault(); }
                } else cache.queried(context, (Integer) args[0], (String) args[1], location);
            } else if (name.equals("glGetError") && result instanceof Integer error) {
                if (error != 0) glErrors++;
                cache.checkedError(error);
            }
            if (name.equals("glDrawElements") || name.equals("glDrawArrays")) draws++;
            if (result == downstream && name.startsWith("getGL") && method.getReturnType().isInstance(proxy)) return proxy;
            return result;
        } catch (InvocationTargetException failure) {
            cache.fault(); failures++;
            throw failure.getCause();
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "TurboismValidationUniformLocationTrial";
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>(cache.snapshot());
        result.put("queries", queries); result.put("nativeQueries", nativeQueries);
        result.put("skippedQueries", skippedQueries); result.put("shadowQueries", shadowQueries);
        result.put("shadowMismatches", shadowMismatches); result.put("glErrors", glErrors);
        result.put("failures", failures); result.put("draws", draws); result.put("displayFrames", frames);
        return result;
    }
    void requireValid() {
        if (queries == 0 || draws == 0 || failures != 0 || glErrors != 0 || shadowMismatches != 0
            || (shadow ? shadowQueries == 0 : skippedQueries == 0)) {
            throw new IllegalStateException("uniform trial invalid: " + snapshot());
        }
    }
    @Override public void close() throws Exception {
        enabled = false; cache.end();
        if (drawable == null) return;
        removeListener.invoke(drawable, beforeListener);
        removeListener.invoke(drawable, afterListener);
        if (getGl.invoke(drawable) != pipeline) throw new IllegalStateException("GL owner changed; not overwriting");
        setGl.invoke(drawable, downstream);
        if (getGl.invoke(drawable) != downstream) throw new IllegalStateException("GL restore failed");
        drawable = null;
    }
}
