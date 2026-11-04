package dev.turboism.adapter.cubism.physics;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

/** Reads the preview framebuffer on its own JOGL context; never captures the desktop. */
final class PhysicsPreviewCapture implements AutoCloseable {
    private final Object drawable;
    private final Class<?> runnableType;
    private final Class<?> drawableType;
    private final Object reader;
    private final Class<?> glType;

    PhysicsPreviewCapture(final Object panel) throws Exception {
        final Object wrapper = invoke(panel, "getViewPanel");
        drawable = invoke(wrapper, "getRaw_glJPanel");
        final ClassLoader loader = drawable.getClass().getClassLoader();
        runnableType = Class.forName("com.jogamp.opengl.GLRunnable", false, loader);
        drawableType = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        glType = Class.forName("com.jogamp.opengl.GL", false, loader);
        final Class<?> profileType = Class.forName("com.jogamp.opengl.GLProfile", false, loader);
        final Object profile = invoke(wrapper, "getGlProfile");
        reader = Class.forName("com.jogamp.opengl.util.awt.AWTGLReadBufferUtil", true, loader)
                .getConstructor(profileType, boolean.class)
                .newInstance(profile, false);
    }

    BufferedImage capture() throws Exception {
        final AtomicReference<BufferedImage> pixels = new AtomicReference<>();
        withContext(gl -> pixels.set((BufferedImage) reader.getClass()
                .getMethod("readPixelsToBufferedImage", glType, boolean.class)
                .invoke(reader, gl, true)));
        if (pixels.get() == null) throw new IllegalStateException("Preview framebuffer unavailable");
        return pixels.get();
    }

    @Override
    public void close() throws Exception {
        withContext(gl -> reader.getClass().getMethod("dispose", glType).invoke(reader, gl));
    }

    private void withContext(final GlAction action) throws Exception {
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final Object callback = Proxy.newProxyInstance(
                runnableType.getClassLoader(), new Class<?>[] {runnableType}, (proxy, method, arguments) -> {
                    if ("run".equals(method.getName())) {
                        try {
                            final Object gl = drawableType.getMethod("getGL").invoke(arguments[0]);
                            action.run(gl);
                        } catch (Exception exception) {
                            failure.set(exception);
                        }
                        return true;
                    }
                    if ("toString".equals(method.getName())) return "Turboism GIF capture";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    return arguments != null && arguments.length == 1 && proxy == arguments[0];
                });
        drawableType.getMethod("invoke", boolean.class, runnableType).invoke(drawable, true, callback);
        if (failure.get() != null) throw failure.get();
    }

    @FunctionalInterface
    private interface GlAction {
        void run(Object gl) throws Exception;
    }

    static Object invoke(final Object target, final String name) throws Exception {
        final var method = MethodHandleCache.declaredByArity(target.getClass(), name, 0);
        ReflectAccess.open(method);
        return method.invoke(target);
    }
}
