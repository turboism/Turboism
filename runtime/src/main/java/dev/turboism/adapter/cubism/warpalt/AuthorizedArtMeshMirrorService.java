package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;

/** Per-plugin authorization and disposable lifetime for ArtMesh mirroring. */
public final class AuthorizedArtMeshMirrorService implements ArtMeshMirrorService {
    private final RuntimeArtMeshMirrorService delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;

    public AuthorizedArtMeshMirrorService(
            RuntimeArtMeshMirrorService delegate, PermissionChecker permissions, DisposableScope scope) {
        this.delegate = Objects.requireNonNull(delegate);
        this.permissions = Objects.requireNonNull(permissions);
        this.scope = Objects.requireNonNull(scope);
    }

    @Override
    public Registration participate() {
        check();
        Registration registration = delegate.participate();
        try {
            return scope.register(registration);
        } catch (RuntimeException | Error failure) {
            registration.close();
            throw failure;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        check();
        delegate.setEnabled(enabled);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void resetAxis() {
        check();
        delegate.resetAxis();
    }

    @Override
    public boolean nativeMirrorActive() {
        return delegate.nativeMirrorActive();
    }

    private void check() {
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "cubism.artmesh.mirror");
    }
}
