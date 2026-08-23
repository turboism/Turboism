package dev.turboism.core.runtime;

import java.util.Objects;

/** Temporarily binds the calling thread to one plugin ClassLoader. */
public final class ContextClassLoaderScope implements AutoCloseable {

    private final Thread thread;
    private final ClassLoader previous;

    private ContextClassLoaderScope(final ClassLoader classLoader) {
        thread = Thread.currentThread();
        previous = thread.getContextClassLoader();
        thread.setContextClassLoader(Objects.requireNonNull(classLoader, "classLoader"));
    }

    /** Binds the current thread context ClassLoader until the returned scope is closed. */
    public static ContextClassLoaderScope bind(final ClassLoader classLoader) {
        return new ContextClassLoaderScope(classLoader);
    }

    @Override
    public void close() {
        thread.setContextClassLoader(previous);
    }
}
