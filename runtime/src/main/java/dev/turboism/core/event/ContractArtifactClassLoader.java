package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * Class loader bound to one published public event contract artifact. All plugins
 * referencing the same artifact bytes share this loader, which is what gives the contract
 * event types a single runtime {@link Class} identity across provider and consumers.
 *
 * <p>Class names owned by the artifact resolve child-first so a same-named class on the
 * parent chain (host classpath or framework) can never preempt the bound contract
 * identity. Every other name delegates to the restricted {@link SdkContractParent}.</p>
 */
class ContractArtifactClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final Set<String> ownedClassNames;

    ContractArtifactClassLoader(final URL artifact, final Set<String> ownedClassNames) {
        super(
            new URL[] {artifact},
            new SdkContractParent(EventBus.class.getClassLoader())
        );
        this.ownedClassNames = Set.copyOf(ownedClassNames);
    }

    boolean owns(final String binaryName) {
        return ownedClassNames.contains(binaryName);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
        if (!ownedClassNames.contains(name)) {
            // Strict delegation: non-owned names never reach findClass, so the artifact
            // JAR (and any manifest Class-Path it could declare) cannot extend the allowed
            // payload closure beyond the restricted parent view.
            final Class<?> loaded = getParent().loadClass(name);
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = findClass(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public void close() throws java.io.IOException {
        closeDelegate();
    }

    /**
     * The real {@link URLClassLoader#close()} delegate. Tests may force a disposal
     * failure here to exercise the catalog's sticky-failure binding retirement.
     */
    void closeDelegate() throws java.io.IOException {
        super.close();
    }
}
