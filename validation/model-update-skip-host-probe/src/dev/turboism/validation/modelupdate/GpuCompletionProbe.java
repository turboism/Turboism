package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Validation-only attribution experiment. An appended JOGL display callback waits
 * for the host's already-submitted GPU work before GLJPanel's readback. glFinish
 * deliberately perturbs scheduling; these timings must never prove a speedup.
 * Uses public JOGL methods on the task-identified drawable, not private Cubism fields.
 */
final class GpuCompletionProbe implements AutoCloseable {
    private final Component drawable;
    private final Object listener;
    private final Method remove;
    private final LongAdder samples = new LongAdder(), nanos = new LongAdder();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean collecting;
    private String capabilities;

    GpuCompletionProbe(final Component drawable) throws Exception {
        this.drawable = drawable;
        final ClassLoader loader = drawable.getClass().getClassLoader();
        final Class<?> drawableType = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        final Class<?> listenerType = Class.forName("com.jogamp.opengl.GLEventListener", false, loader);
        final Class<?> glType = Class.forName("com.jogamp.opengl.GL", false, loader);
        final Method getGl = drawableType.getMethod("getGL");
        final Method finish = glType.getMethod("glFinish");
        remove = drawableType.getMethod("removeGLEventListener", listenerType);
        capabilities = String.valueOf(drawable.getClass().getMethod("getChosenGLCapabilities").invoke(drawable));
        listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "TurboismTaskGpuCompletionProbe";
                };
            }
            if (method.getName().equals("display") && collecting && failure.get() == null) {
                try {
                    if (args == null || args[0] != drawable) throw new IllegalStateException("drawable identity changed");
                    final Object gl = getGl.invoke(drawable);
                    final long started = System.nanoTime();
                    finish.invoke(gl);
                    nanos.add(System.nanoTime() - started);
                    samples.increment();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    collecting = false;
                }
            }
            return null;
        });
        drawableType.getMethod("addGLEventListener", listenerType).invoke(drawable, listener);
    }

    void start() { collecting = true; }
    void stop() { collecting = false; }

    String report() {
        final Throwable problem = failure.get();
        return "gpuCompletion.capabilities=" + capabilities + "\n"
            + "gpuCompletion.samples=" + samples.sum() + "\n"
            + "gpuCompletion.totalNanos=" + nanos.sum() + "\n"
            + "gpuCompletion.meanNanos=" + (samples.sum() == 0L ? -1L : nanos.sum() / samples.sum()) + "\n"
            + "gpuCompletion.failure=" + (problem == null ? "none" : problem.toString()) + "\n";
    }

    void requireValid() {
        if (failure.get() != null || samples.sum() == 0L) {
            throw new IllegalStateException("GPU attribution failed", failure.get());
        }
    }

    @Override public void close() throws Exception {
        stop();
        remove.invoke(drawable, listener);
    }
}
