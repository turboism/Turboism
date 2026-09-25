package dev.turboism.adapter.cubism.physics;

import dev.turboism.sdk.cubism.physics.PhysicsEditorContribution;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Per-plugin view of the shared {@link PhysicsEditorCoordinator}. Contributions
 * registered through this service are owned by the plugin scope: they are
 * detached automatically when the scope closes, stale services reject new
 * contributions, and closing one plugin's scope never closes the shared
 * coordinator or removes another plugin's contribution.
 */
public final class PluginScopedPhysicsEditorService implements PhysicsEditorService {

    private final PhysicsEditorService delegate;
    private final DisposableScope scope;
    private final BooleanSupplier scopeActive;

    /**
     * @param delegate shared physics editor service that owns the contribution slot
     * @param scope owning plugin scope; contributions are released when it closes
     * @param scopeActive liveness of the owning plugin scope
     */
    public PluginScopedPhysicsEditorService(
        final PhysicsEditorService delegate,
        final DisposableScope scope,
        final BooleanSupplier scopeActive
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.scopeActive = Objects.requireNonNull(scopeActive, "scopeActive");
    }

    @Override
    public Registration contribute(final PhysicsEditorContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (!scopeActive.getAsBoolean()) {
            throw new IllegalStateException(
                "Cubism physics editor service reference is stale because the owning plugin is disabled."
            );
        }
        final Registration inner = delegate.contribute(contribution);
        // DisposableScope.close() and the returned Registration handle do not share once
        // state with each other, so both paths funnel through this single release action:
        // whichever runs first detaches the contribution; every later call is a no-op and
        // can never revoke a contribution owned by another plugin.
        final AtomicBoolean released = new AtomicBoolean();
        final Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                inner.close();
            }
        };
        final Registration handle;
        try {
            handle = scope.register(release::run);
        } catch (IllegalStateException scopeClosed) {
            // The scope closed between the liveness check and registration: release the
            // just-claimed contribution slot instead of leaking it.
            release.run();
            throw scopeClosed;
        }
        return handle::close;
    }
}
