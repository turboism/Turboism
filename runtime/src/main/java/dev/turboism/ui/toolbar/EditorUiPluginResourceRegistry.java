package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Exact plugin-generation lookup for resources owned by loaded UI contributors. */
public final class EditorUiPluginResourceRegistry implements AutoCloseable {
    private final ConcurrentHashMap<Key, ClassLoader> loaders = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** Compatibility generation-zero registration. */
    public Registration register(final String pluginId, final ClassLoader classLoader) {
        return register(pluginId, 0L, classLoader);
    }

    /** Registers a class loader for one exact plugin generation. */
    public Registration register(final String pluginId, final long pluginGeneration, final ClassLoader classLoader) {
        final Key key = new Key(text(pluginId, "pluginId"), generation(pluginGeneration));
        final ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        if (closed) throw new IllegalStateException("Editor UI plugin resource registry is closed");
        final ClassLoader previous = loaders.putIfAbsent(key, loader);
        if (previous != null && previous != loader) {
            throw new IllegalStateException("Plugin generation resource loader is already registered");
        }
        return () -> loaders.remove(key, loader);
    }

    /** Compatibility generation-zero lookup. */
    public Optional<URL> resource(final String pluginId, final String resourcePath) {
        return resource(pluginId, 0L, resourcePath);
    }

    /** Resolves a resource owned by one exact plugin generation. */
    public Optional<URL> resource(final String pluginId, final long pluginGeneration, final String resourcePath) {
        final String path = resourcePath(resourcePath);
        if (closed) return Optional.empty();
        final ClassLoader loader = loaders.get(new Key(text(pluginId, "pluginId"), generation(pluginGeneration)));
        return loader == null ? Optional.empty() : Optional.ofNullable(loader.getResource(path));
    }

    @Override
    public void close() {
        closed = true;
        loaders.clear();
    }

    private static String resourcePath(final String value) {
        final String path = text(value, "resourcePath");
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException("resourcePath must be a normalized classpath resource");
        }
        return path;
    }

    private static long generation(final long value) {
        if (value < 0) throw new IllegalArgumentException("pluginGeneration must not be negative");
        return value;
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Key(String pluginId, long generation) {}
}
