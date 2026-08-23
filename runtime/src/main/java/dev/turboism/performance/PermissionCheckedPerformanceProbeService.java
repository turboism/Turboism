package dev.turboism.performance;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/** Plugin-scoped permission boundary over the session-owned performance sampler. */
public final class PermissionCheckedPerformanceProbeService
    implements PerformanceProbeService {

    private final PerformanceProbeService delegate;
    private final PermissionChecker permissionChecker;

    public PermissionCheckedPerformanceProbeService(
        final PerformanceProbeService delegate,
        final PermissionChecker permissionChecker
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.permissionChecker = Objects.requireNonNull(
            permissionChecker,
            "permissionChecker"
        );
    }

    @Override
    public PerformanceSnapshot snapshot() {
        checkPermission();
        return delegate.snapshot();
    }

    @Override
    public Registration sample(
        final Duration interval,
        final Consumer<PerformanceSnapshot> consumer
    ) {
        checkPermission();
        return delegate.sample(interval, consumer);
    }

    private void checkPermission() {
        permissionChecker.check(
            PermissionIds.TURBOISM_PERFORMANCE_STATS_READ,
            "performance.stats.read"
        );
    }
}
