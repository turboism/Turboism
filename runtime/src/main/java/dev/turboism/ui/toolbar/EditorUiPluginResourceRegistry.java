package dev.turboism.ui.toolbar;

import dev.turboism.sdk.plugin.Registration;

import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Connection-safe lookup for resources owned by loaded UI-contributing plugins. */
public final class EditorUiPluginResourceRegistry implements AutoCloseable {

    private final ConcurrentHashMap<String, ClassLoader> loaders = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public Registration register(final String pluginId, final ClassLoader classLoader) {
        final String id = requireText(pluginId, "pluginId");
        final ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        if (closed) {
            throw new IllegalStateException("Editor UI plugin resource registry is closed");
        }
        final ClassLoader previous = loaders.put(id, loader);
        if (previous != null && previous != loader) {
            loaders.put(id, previous);
            throw new IllegalStateException("Plugin resource loader is already registered");
        }
        return () -> loaders.remove(id, loader);
    }

    public Optional<URL> resource(final String pluginId, final String resourcePath) {
        final ClassLoader loader = loaders.get(requireText(pluginId, "pluginId"));
        if (loader == null || closed) {
            return Optional.empty();
        }
        final String normalized = requireText(resourcePath, "resourcePath");
        if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains("\\")) {
            throw new IllegalArgumentException("resourcePath must be a normalized classpath resource");
        }
        return Optional.ofNullable(loader.getResource(normalized));
    }

    @Override
    public void close() {
        closed = true;
        loaders.clear();
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
