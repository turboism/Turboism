package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class StatusToolbarAdapterImpl implements StatusToolbarAdapter {

    private final Optional<HostOperations> host;

    private StatusToolbarAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public static StatusToolbarAdapter connected(final HostOperations host) {
        return new StatusToolbarAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    public static StatusToolbarAdapter safeMode() {
        return new StatusToolbarAdapterImpl(Optional.empty());
    }

    @Override
    public AdapterResult<Registration> notifyStatus(final StatusNotification notification) {
        Objects.requireNonNull(notification, "notification");
        return withCapability(Capability.STATUS_NOTIFY, operations -> operations.notifyStatus(notification));
    }

    @Override
    public AdapterResult<Registration> contributePaletteToolbar(
        final PaletteToolbarRegistry.PaletteToolbarContribution contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        return withCapability(
            Capability.PALETTE_TOOLBAR_CONTRIBUTE,
            operations -> operations.contributePaletteToolbar(contribution)
        );
    }

    private <T> AdapterResult<T> withCapability(final Capability capability, final HostCall<T> hostCall) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(hostCall, "hostCall");
        return host.map(operations -> callIfSupported(operations, capability, hostCall))
            .orElseGet(unavailable(capability));
    }

    private <T> AdapterResult<T> callIfSupported(
        final HostOperations operations,
        final Capability capability,
        final HostCall<T> hostCall
    ) {
        final Optional<SafeModeDiagnostic> versionDiagnostic = HostUiVersionCheck.diagnosticFor(operations.hostVersion());
        if (versionDiagnostic.isPresent()) {
            return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
        }
        if (!operations.supports(capability)) {
            return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(capability.id()));
        }
        return AdapterResult.available(hostCall.invoke(operations));
    }

    private static <T> Supplier<AdapterResult<T>> unavailable(final Capability capability) {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(capability.id()));
    }

    @FunctionalInterface
    private interface HostCall<T> {
        T invoke(HostOperations operations);
    }
}
