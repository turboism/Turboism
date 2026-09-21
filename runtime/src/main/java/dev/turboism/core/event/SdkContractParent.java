package dev.turboism.core.event;

/**
 * Restricted parent class loader for public event contract artifacts.
 *
 * <p>A published contract may only reference the JDK platform modules and the public SDK
 * surface ({@code dev.turboism.sdk.*}). When the SDK is loaded by the agent bootstrap class
 * loader its loader is {@code null}; that bootstrap view also exposes runtime internals,
 * the built-in core plugin and shaded dependencies, so names must never be delegated to it
 * unfiltered. Two rules apply:</p>
 *
 * <ul>
 *   <li>{@code dev.turboism.sdk.*} resolves through the real SDK loader (bootstrap when it
 *       is {@code null}), preserving the shared SDK identity;</li>
 *   <li>every other name resolves through the platform class loader <em>and</em> is
 *       accepted only when the resolved class belongs to a named {@code java.*}/{@code jdk.*}
 *       module. The platform loader's own parent is bootstrap, which carries the agent JAR
 *       — without the module check, framework and host classpath classes would escape
 *       through it. JDK classes such as {@code com.sun.net.httpserver} (module
 *       {@code jdk.httpserver}) or {@code org.w3c.dom} (module {@code java.xml}) remain
 *       reachable; unnamed-module (classpath/boot-classpath) classes never are.</li>
 * </ul>
 */
final class SdkContractParent extends ClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final String SDK_PREFIX = "dev.turboism.sdk.";

    private final ClassLoader sdkLoader;

    SdkContractParent(final ClassLoader sdkLoader) {
        super(null);
        this.sdkLoader = sdkLoader;
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            final Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            final Class<?> resolved = resolveAllowed(name);
            if (resolve) {
                resolveClass(resolved);
            }
            return resolved;
        }
    }

    private Class<?> resolveAllowed(final String name) throws ClassNotFoundException {
        if (name.startsWith(SDK_PREFIX)) {
            return sdkLoader != null
                ? sdkLoader.loadClass(name)
                : Class.forName(name, false, null);
        }
        final Class<?> candidate =
            ClassLoader.getPlatformClassLoader().loadClass(name);
        final Module module = candidate.getModule();
        if (module == null || !isJdkModule(module)) {
            throw new ClassNotFoundException(name);
        }
        return candidate;
    }

    private static boolean isJdkModule(final Module module) {
        return module.isNamed()
            && (module.getName().startsWith("java.")
                || module.getName().startsWith("jdk."));
    }
}
