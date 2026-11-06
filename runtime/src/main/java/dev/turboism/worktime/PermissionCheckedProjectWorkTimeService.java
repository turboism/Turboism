package dev.turboism.worktime;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService;
import dev.turboism.sdk.cubism.worktime.ProjectWorkTimeSnapshot;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import java.util.Objects;

/** Per-plugin permission boundary over the runtime-shared work-time tracker. */
public final class PermissionCheckedProjectWorkTimeService implements ProjectWorkTimeService, AutoCloseable {

    private final ProjectWorkTimeService delegate;
    private final PermissionChecker permissionChecker;
    private volatile boolean closed;

    public PermissionCheckedProjectWorkTimeService(
            final ProjectWorkTimeService delegate, final PermissionChecker permissionChecker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
    }

    /**
     * Binds a plugin-scoped view to a plugin lifecycle scope.
     *
     * @throws IllegalStateException when the scope is already closed; the wrapper is released
     */
    public static PermissionCheckedProjectWorkTimeService bind(
            final ProjectWorkTimeService delegate,
            final PermissionChecker permissionChecker,
            final DisposableScope scope) {
        final PermissionCheckedProjectWorkTimeService service =
                new PermissionCheckedProjectWorkTimeService(delegate, permissionChecker);
        try {
            Objects.requireNonNull(scope, "scope").register(service);
            return service;
        } catch (RuntimeException | Error failure) {
            // @containment-exempt: closes service before rethrowing with fatal error prioritized
            Throwable toThrow = failure;
            try {
                service.close();
            } catch (RuntimeException | Error cleanup) {
                // @containment-exempt: cleanup failure merged, prioritizing fatal error
                toThrow = FatalErrors.mergeForCleanup(toThrow, cleanup);
            }
            throw FatalErrors.propagate(toThrow);
        }
    }

    @Override
    public boolean isAvailable() {
        return !closed && delegate.isAvailable();
    }

    @Override
    public ProjectWorkTimeSnapshot snapshot() {
        checkPermission();
        if (closed) {
            throw new IllegalStateException("Project work-time scope is closed");
        }
        return delegate.snapshot();
    }

    @Override
    public void close() {
        closed = true;
    }

    private void checkPermission() {
        permissionChecker.check(PermissionIds.TURBOISM_CUBISM_PROJECT_READ, "cubism.project.work-time.read");
    }
}
