package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Per-plugin permission and lifetime boundary over counterpart resolution. */
public final class AuthorizedMeshMirrorCounterparts implements MeshMirrorCounterparts {

    private final RuntimeMeshMirrorCounterparts delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;
    private final AtomicReference<MeshMirrorCounterpartResolver> resolver = new AtomicReference<>();

    public AuthorizedMeshMirrorCounterparts(
        final RuntimeMeshMirrorCounterparts delegate,
        final PermissionChecker permissions,
        final DisposableScope scope
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "cubism.mesh.mirror-counterparts.resolve");
        return delegate.mirrorOf(deletion, resolver.get());
    }

    @Override
    public Registration overrideResolver(final MeshMirrorCounterpartResolver replacement) {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "cubism.mesh.mirror-counterparts.override");
        Objects.requireNonNull(replacement, "resolver");
        if (!resolver.compareAndSet(null, replacement)) {
            throw new IllegalStateException("this plugin already registered a mirror counterpart resolver");
        }
        final Registration registration = () -> resolver.compareAndSet(replacement, null);
        try {
            return scope.register(registration);
        } catch (RuntimeException | Error failure) {
            registration.close();
            throw failure;
        }
    }
}
