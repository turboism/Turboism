package dev.turboism.preview;

/**
 * Narrow parent-boundary filter for external plugin classloaders. Plugin loaders delegate
 * parent-first, so without this filter an external plugin could link against implementation
 * namespaces carried by the agent jar: {@code dev.turboism.internal.*} management contracts
 * (built-in-only services), {@code dev.turboism.plugin.core.*} core UI classes, and
 * {@code dev.turboism.agent.shaded.*} relocated private libraries. The wrapper refuses those
 * names before delegation; every other class — SDK types, JDK platform modules — resolves
 * exactly as before. The built-in core never loads through this loader: it is constructed on
 * the application classpath by its entrypoint, so its contract access is unaffected.
 */
final class PluginParentBoundary extends ClassLoader {
    private static final String[] DENIED_PREFIXES = {
        "dev.turboism.internal.",
        "dev.turboism.plugin.core.",
        "dev.turboism.agent.shaded."
    };

    private PluginParentBoundary(final ClassLoader delegate) {
        super(delegate);
    }

    static ClassLoader denyImplementationNamespaces(final ClassLoader delegate) {
        return new PluginParentBoundary(delegate);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
        for (final String prefix : DENIED_PREFIXES) {
            if (name.startsWith(prefix)) {
                throw new ClassNotFoundException(
                    name + " is implementation-internal, not a plugin-facing API"
                );
            }
        }
        return super.loadClass(name, resolve);
    }
}
