package dev.turboism.automesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithmRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin-generation-owned view of the session auto-mesh catalog.
 *
 * <p>One instance backs one plugin generation and publishes into the shared {@link
 * AutoMeshAlgorithmCatalog}, which owns the Editor's generator and the session selection. Closing
 * this registry fences the generation: its liveness probe turns false, so its algorithms stop being
 * listed and resolved before the catalog bookkeeping finishes, and closing twice has no effect.</p>
 */
public final class RuntimeAutoMeshAlgorithmRegistry implements AutoMeshAlgorithmRegistry {

    /** Capability a plugin declares before it may register an algorithm. */
    public static final String REQUIRED_CAPABILITY = "cubism.mesh.automesh-algorithms";

    private final String pluginId;
    private final long pluginGeneration;
    private final PermissionChecker permissions;
    private final boolean capabilityGranted;
    private final AutoMeshAlgorithmCatalog catalog;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object monitor = new Object();
    private final Map<String, Registration> registrations = new HashMap<>();

    public RuntimeAutoMeshAlgorithmRegistry(
            final String pluginId,
            final long pluginGeneration,
            final PermissionChecker permissions,
            final boolean capabilityGranted,
            final AutoMeshAlgorithmCatalog catalog) {
        this.pluginId = text(pluginId, "pluginId");
        if (pluginGeneration < 0) {
            throw new IllegalArgumentException("pluginGeneration must not be negative");
        }
        this.pluginGeneration = pluginGeneration;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.capabilityGranted = capabilityGranted;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Returns the exact plugin generation owned by this registry. */
    public long pluginGeneration() {
        return pluginGeneration;
    }

    /** Binds registry cleanup to the plugin's disposable scope. */
    public void bind(final DisposableScope scope) {
        Objects.requireNonNull(scope, "scope").register(this);
    }

    @Override
    public Registration register(final AutoMeshAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        requireAuthorized("autoMeshAlgorithms.register");
        // Registration publishes canonical preset text, so a stored or rendered preset never
        // depends on how the plugin spelled a number.
        final AutoMeshAlgorithm canonical = AutoMeshValueCodec.canonicalPresets(algorithm);
        synchronized (monitor) {
            requireOpen();
            if (registrations.containsKey(canonical.id())) {
                throw new IllegalStateException(
                        "auto-mesh algorithm id is already registered by this plugin generation: " + canonical.id());
            }
            final Registration published = catalog.register(canonical, pluginId, pluginGeneration, () -> !closed.get());
            registrations.put(canonical.id(), published);
            return () -> {
                synchronized (monitor) {
                    registrations.remove(canonical.id(), published);
                }
                published.close();
            };
        }
    }

    @Override
    public List<AutoMeshAlgorithm> algorithms() {
        return catalog.algorithms();
    }

    @Override
    public Optional<AutoMeshAlgorithm> find(final String algorithmId) {
        return catalog.find(algorithmId);
    }

    @Override
    public void select(final String algorithmId) {
        requireAuthorized("autoMeshAlgorithms.select");
        catalog.select(algorithmId);
    }

    @Override
    public Optional<String> selectedAlgorithmId() {
        return catalog.selectedAlgorithmId();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final List<Registration> owned;
        synchronized (monitor) {
            owned = new ArrayList<>(registrations.values());
            registrations.clear();
        }
        RuntimeException first = null;
        for (int index = owned.size() - 1; index >= 0; index--) {
            try {
                owned.get(index).close();
            } catch (RuntimeException failure) {
                first = append(first, failure);
            }
        }
        try {
            catalog.revokeAll(pluginId, pluginGeneration);
        } catch (RuntimeException failure) {
            first = append(first, failure);
        }
        if (first != null) {
            throw first;
        }
    }

    private void requireAuthorized(final String operation) {
        if (!capabilityGranted) {
            throw new UnsupportedOperationException("plugin capability " + REQUIRED_CAPABILITY + " is required");
        }
        permissions.check(PermissionIds.TURBOISM_CUBISM_MESH_AUTOMESH_CONTRIBUTE, operation);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("auto-mesh algorithm registry is closed");
        }
    }

    private static RuntimeException append(final RuntimeException first, final RuntimeException next) {
        if (next == null) {
            return first;
        }
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
