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
        try {
            final Optional<SafeModeDiagnostic> versionDiagnostic =
                HostUiVersionCheck.diagnosticFor(capability.id(), operations.hostVersion());
            if (versionDiagnostic.isPresent()) {
                return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
            }
            if (!operations.supports(capability)) {
                return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(capability.id()));
            }
            return AdapterResult.available(hostCall.invoke(operations));
        } catch (AdapterHostException exception) {
            return AdapterResult.unavailable(exception.diagnostic());
        } catch (RuntimeException exception) {
            return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                capability.id(),
                "Host status/toolbar adapter call failed safely."
            ));
        }
    }

    private static <T> Supplier<AdapterResult<T>> unavailable(final Capability capability) {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(capability.id()));
    }

    @FunctionalInterface
    private interface HostCall<T> {
        T invoke(HostOperations operations);
    }
}
