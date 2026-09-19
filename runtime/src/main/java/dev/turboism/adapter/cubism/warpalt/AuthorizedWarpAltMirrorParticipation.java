package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Per-plugin permission and lifetime boundary over the warp Alt-mirror policy. */
public final class AuthorizedWarpAltMirrorParticipation implements WarpAltMirrorParticipation {

    private final RuntimeWarpAltMirrorParticipation delegate;
    private final PermissionChecker permissions;
    private final DisposableScope scope;

    public AuthorizedWarpAltMirrorParticipation(
        final RuntimeWarpAltMirrorParticipation delegate,
        final PermissionChecker permissions,
        final DisposableScope scope
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public Registration participate() {
        permissions.check(
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
            "cubism.warp.alt-symmetry.participate"
        );
        final Registration registration = delegate.participate();
        try {
            return scope.register(registration);
        } catch (RuntimeException | Error failure) {
            registration.close();
            throw failure;
        }
    }

    @Override
    public boolean nativeMirrorActive() {
        return delegate.nativeMirrorActive();
    }

    @Override
    public void setArmedAxis(final int axis) {
        delegate.setArmedAxis(axis);
    }

    @Override
    public void setLiveCtrlDown(final boolean down) {
        delegate.setLiveCtrlDown(down);
    }
}
