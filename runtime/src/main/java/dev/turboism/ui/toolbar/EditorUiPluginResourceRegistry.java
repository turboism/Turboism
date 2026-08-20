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

    /**
     * Registers the class loader that owns one plugin's resources.
     *
     * <p>One loader per plugin id: re-registering the same loader is accepted, but a different
     * loader for an id that is already taken is refused and the existing mapping is restored.
     *
     * @param pluginId non-blank id of the contributing plugin
     * @param classLoader the loader whose classpath resources may be looked up for that id
     * @return a registration that removes the mapping, and only this exact mapping
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     * @throws IllegalStateException if the registry is closed, or a different loader is already
     *     registered for {@code pluginId}
     */
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

    /**
     * Resolves a resource inside one plugin's own classpath, so a UI contribution cannot read
     * another plugin's or the host's resources through this registry.
     *
     * <p>The path is required to be a normalized, relative classpath name: a leading {@code /},
     * any {@code ..} segment, or a backslash is rejected rather than resolved.
     *
     * @param pluginId non-blank id of the owning plugin
     * @param resourcePath non-blank normalized classpath resource name
     * @return the resource URL, or empty when the plugin is not registered, the registry is
     *     closed, or the loader has no such resource
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is blank, or {@code resourcePath} is
     *     absolute, contains {@code ..}, or contains a backslash
     */
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
