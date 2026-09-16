package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validation-only GL call attribution. Forwards every native call exactly once;
 * does not cache, omit, reorder or add OpenGL commands. Reflection/timing overhead
 * makes this a diagnostic, never an uninstrumented performance result.
 * Attach/detach and collection boundaries must run on the task drawable's EDT.
 */
final class GlSubmissionProbe implements InvocationHandler, AutoCloseable {
    private static final class Metric {
        long calls, nanos, maximum, bytes, exceptions, nonzeroErrors;
        void clear() { calls = nanos = maximum = bytes = exceptions = nonzeroErrors = 0L; }
    }
    private final Object downstream;
    private final Object pipeline;
    private final String apiName;
    private final UploadPayloadObserver payloads;
    private final Method contextGetter;
    private final Map<String, Metric> metrics = new TreeMap<>();
    private final Map<Method, Metric> methods = new LinkedHashMap<>();
    private volatile boolean collecting;
    private Component drawable;
    private Method getGl, setGl;

    GlSubmissionProbe(Class<?> api, Object downstream) {
        if (!api.isInterface() || !api.isInstance(downstream)) {
            throw new IllegalArgumentException("GL delegate does not implement the selected API");
        }
        this.downstream = downstream;
        this.apiName = api.getName();
        boolean observePayloads = Boolean.getBoolean("turboism.validation.modelUpdateUploadPayloads");
        payloads = observePayloads ? new UploadPayloadObserver() : null;
        Method getContext = null;
        if (observePayloads) {
            try { getContext = api.getMethod("getContext"); }
            catch (NoSuchMethodException absent) {
                throw new IllegalArgumentException("payload observation requires the public GL context accessor", absent);
            }
        }
        contextGetter = getContext;
        for (Method method : api.getMethods()) {
            if (method.getName().startsWith("gl")) {
                methods.put(method, metrics.computeIfAbsent(method.getName(), key -> new Metric()));
            }
        }
        pipeline = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, this);
    }

    static GlSubmissionProbe attach(Component drawable) throws Exception {
        ClassLoader loader = drawable.getClass().getClassLoader();
        Class<?> autoDrawable = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        Class<?> gl = Class.forName("com.jogamp.opengl.GL", false, loader);
        // GL4bc has 2926 inherited methods and exceeds the JDK Proxy method-size
        // limit. The verified renderer obtains GL3 (942 methods), which also covers
        // its GL2ES2 calls and GLJPanel's GL2ES3 view. Do not use only GL2ES3: the
        // renderer's getGL3() would escape that decorator and miss its submissions.
        Class<?> api = Class.forName("com.jogamp.opengl.GL3", false, loader);
        Method get = autoDrawable.getMethod("getGL");
        Method set = autoDrawable.getMethod("setGL", gl);
        Object original = get.invoke(drawable);
        GlSubmissionProbe probe = new GlSubmissionProbe(api, original);
        probe.drawable = drawable;
        probe.getGl = get;
        probe.setGl = set;
        try {
            set.invoke(drawable, probe.pipeline);
            if (get.invoke(drawable) != probe.pipeline) {
                throw new IllegalStateException("GL decorator was not installed");
            }
            return probe;
        } catch (Throwable failure) {
            if (get.invoke(drawable) == probe.pipeline) set.invoke(drawable, original);
            throw failure;
        }
    }

    Object wrapped() { return pipeline; }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "TurboismValidationGlAttribution[" + downstream.getClass().getName() + "]";
                default -> method.invoke(downstream, args);
            };
        }
        Metric metric = collecting ? methods.get(method) : null;
        boolean identicalPayload = false;
        if (metric != null && payloads != null && observesBufferState(method.getName())) {
            try { identicalPayload = payloads.before(contextGetter.invoke(downstream), method.getName(), args); }
            catch (Throwable observationFailure) { payloads.observerFailure(); }
        }
        // Exclude the diagnostic payload scan from the native-delegate timer.
        long started = metric == null ? 0L : System.nanoTime();
        long returnedAt = 0L;
        try {
            Object result = method.invoke(downstream, args);
            if (metric != null) returnedAt = System.nanoTime();
            if (metric != null) {
                if (method.getName().equals("glBufferSubData") && args != null && args.length > 2) {
                    metric.bytes += ((Number) args[2]).longValue();
                } else if (method.getName().equals("glBufferData") && args != null && args.length > 1) {
                    metric.bytes += ((Number) args[1]).longValue();
                } else if (method.getName().equals("glGetError") && result instanceof Number value
                    && value.intValue() != 0) {
                    metric.nonzeroErrors++;
                    if (payloads != null) payloads.nativeFailure();
                }
                if (identicalPayload) {
                    payloads.completed(true, ((Number) args[2]).longValue(), returnedAt - started);
                }
            }
            // JOGL's GL view accessors must keep calls in this decorator. Root/context
            // access remains the downstream implementation's original behavior.
            if (result == downstream && method.getName().startsWith("getGL")
                && method.getReturnType().isInstance(proxy)) return proxy;
            return result;
        } catch (InvocationTargetException failure) {
            if (metric != null) {
                metric.exceptions++;
                if (payloads != null) payloads.nativeFailure();
            }
            throw failure.getCause();
        } finally {
            if (metric != null) {
                long elapsed = (returnedAt == 0L ? System.nanoTime() : returnedAt) - started;
                metric.calls++;
                metric.nanos += elapsed;
                metric.maximum = Math.max(metric.maximum, elapsed);
            }
        }
    }

    private static boolean observesBufferState(String name) {
        return name.startsWith("glBindBuffer") || name.startsWith("glBuffer")
            || name.startsWith("glDeleteBuffers") || name.startsWith("glNamedBuffer")
            || name.startsWith("glMap") || name.startsWith("glUnmap")
            || name.startsWith("glFlushMapped") || name.startsWith("glCopyBuffer")
            || name.startsWith("glCopyNamedBuffer") || name.startsWith("glClearBufferData")
            || name.startsWith("glClearBufferSubData") || name.startsWith("glClearNamedBuffer")
            || name.startsWith("glInvalidateBuffer");
    }

    void start() {
        metrics.values().forEach(Metric::clear);
        if (payloads != null) payloads.start();
        collecting = true;
    }
    void stop() {
        collecting = false;
        if (payloads != null) payloads.stop();
    }

    String report() {
        StringBuilder out = new StringBuilder("glCalls.instrumented=true\n")
            .append("glCalls.api=").append(apiName).append('\n')
            .append("glCalls.delegate=").append(downstream.getClass().getName()).append('\n');
        metrics.forEach((name, metric) -> {
            if (metric.calls == 0) return;
            String prefix = "glCalls." + name + ".";
            out.append(prefix).append("calls=").append(metric.calls).append('\n')
                .append(prefix).append("nanos=").append(metric.nanos).append('\n')
                .append(prefix).append("maxNanos=").append(metric.maximum).append('\n')
                .append(prefix).append("bytes=").append(metric.bytes).append('\n')
                .append(prefix).append("exceptions=").append(metric.exceptions).append('\n')
                .append(prefix).append("nonzeroErrors=").append(metric.nonzeroErrors).append('\n');
        });
        if (payloads != null) out.append(payloads.report());
        return out.toString();
    }

    void requireValid() {
        if (payloads != null && payloads.failed()) throw new IllegalStateException("payload observation failed");
        long calls = metrics.values().stream().mapToLong(value -> value.calls).sum();
        long errors = metrics.values().stream().mapToLong(value -> value.exceptions + value.nonzeroErrors).sum();
        long uploads = callsOf("glBufferData") + callsOf("glBufferSubData");
        long draws = callsOf("glDrawElements") + callsOf("glDrawArrays");
        long readbacks = callsOf("glReadPixels");
        // This probe is scoped to the known heavy-model GLJPanel workload. Observing
        // only the panel's readback or setup queries does not prove we wrapped the
        // renderer's cached GL3 view. Missing any family invalidates attribution.
        if (calls == 0 || errors != 0 || uploads == 0 || draws == 0 || readbacks == 0) {
            throw new IllegalStateException("GL attribution: calls=" + calls + " errors=" + errors
                + " uploads=" + uploads + " draws=" + draws + " readbacks=" + readbacks);
        }
    }

    private long callsOf(String name) {
        Metric metric = metrics.get(name);
        return metric == null ? 0L : metric.calls;
    }

    @Override public void close() throws Exception {
        stop();
        if (drawable == null) return;
        Object current = getGl.invoke(drawable);
        if (current != pipeline) throw new IllegalStateException("GL pipeline ownership changed; not overwriting");
        setGl.invoke(drawable, downstream);
        if (getGl.invoke(drawable) != downstream) throw new IllegalStateException("GL pipeline restoration failed");
        drawable = null;
    }
}
