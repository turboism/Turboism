package dev.turboism.preview;

import dev.turboism.core.event.PublicEventContractCatalog;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Objects;

/**
 * Plugin artifact class loader with public event contract delegation.
 *
 * <p>Class names owned by the plugin's bound contract artifacts resolve through the
 * session-shared contract loader <em>before</em> the parent chain or the plugin's own JAR,
 * so a same-named class on the host classpath can never preempt the bound contract
 * identity and provider/consumers observe one {@link Class}. All other names follow the
 * standard parent-first delegation to {@link PreviewPluginLoader#resolvePluginParent} —
 * the core boundary helper may wrap that parent independently, and contract delegation can
 * never bypass it because contract artifacts cannot contain framework package names.</p>
 *
 * <p>The loader owns its {@link PublicEventContractCatalog.ContractLease}: the lease is
 * released only after a successful {@link URLClassLoader#close()}. A failed close is
 * sticky — the stored failure is rethrown on every later close attempt and the lease is
 * never released after unproven disposal, so a retained generation keeps its contract
 * bindings instead of falsely reporting cleanup.</p>
 */
class PluginContractClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final Map<String, ClassLoader> contractDelegates;
    private final PublicEventContractCatalog.ContractLease contractLease;
    private IOException closeFailure;

    PluginContractClassLoader(
        final URL[] urls,
        final ClassLoader parent,
        final PublicEventContractCatalog.ContractLease contractLease
    ) {
        super(urls, parent);
        this.contractLease = Objects.requireNonNull(contractLease, "contractLease");
        this.contractDelegates = contractLease.delegates();
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
        final ClassLoader contract = contractDelegates.get(name);
        if (contract == null) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            final Class<?> loaded = contract.loadClass(name);
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public void close() throws IOException {
        if (closeFailure != null) {
            throw closeFailure;
        }
        try {
            closeDelegate();
        } catch (IOException | RuntimeException failure) {
            closeFailure = failure instanceof IOException io
                ? io
                : new IOException(failure);
            throw closeFailure;
        }
        contractLease.close();
    }

    /**
     * The real {@link URLClassLoader#close()} delegate. Tests may force a disposal
     * failure here to exercise the sticky-failure rule without corrupting a live JAR.
     */
    void closeDelegate() throws IOException {
        super.close();
    }
}
