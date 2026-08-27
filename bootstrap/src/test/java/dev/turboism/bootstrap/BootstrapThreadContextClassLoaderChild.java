package dev.turboism.bootstrap;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

/** Child-JVM fixture proving the bootstrap thread cannot seed Swing with a null context loader. */
public final class BootstrapThreadContextClassLoaderChild {

    private BootstrapThreadContextClassLoaderChild() {
    }

    public static void main(final String[] ignored) throws Exception {
        if (BootstrapThreadFactory.class.getClassLoader() != null) {
            throw new AssertionError("fixture did not load the bootstrap thread factory from bootstrap");
        }
        final AtomicReference<ClassLoader> edtLoader = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread bootstrap = BootstrapThreadFactory.create(() -> {
            try {
                SwingUtilities.invokeAndWait(() ->
                    edtLoader.set(Thread.currentThread().getContextClassLoader())
                );
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        bootstrap.start();
        bootstrap.join();

        if (failure.get() != null) {
            throw new AssertionError("Swing dispatch failed", failure.get());
        }
        if (edtLoader.get() != ClassLoader.getSystemClassLoader()) {
            throw new AssertionError("Swing EDT inherited the wrong context classloader");
        }
    }
}
