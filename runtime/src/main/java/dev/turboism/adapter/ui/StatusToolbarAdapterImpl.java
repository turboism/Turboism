package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default {@link StatusToolbarAdapter} that either wraps live host operations or serves a
 * permanently-degraded safe mode.
 *
 * <p>Every call is gated twice: the host version must sit inside the scope reviewed for the
 * capability, and the host must report the capability as supported. Host failures never escape --
 * an {@link AdapterHostException} becomes its own diagnostic and any other {@link RuntimeException}
 * becomes a validation-failure diagnostic with runtime-authored text.</p>
 */
public final class StatusToolbarAdapterImpl implements StatusToolbarAdapter {

    private final Optional<HostOperations> host;

    private StatusToolbarAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * @param host live host operations to delegate to; per-call version and capability gating still
     *     applies, so a connected adapter can still return diagnostics
     * @return an adapter bound to the given host
     * @throws NullPointerException if {@code host} is null
     */
    public static StatusToolbarAdapter connected(final HostOperations host) {
        return new StatusToolbarAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    /**
     * Verified-CX composition seam for the reviewed exact-version (5.2.03,
     * 5.3.02 or 5.3.03) status slice: wraps the
     * package-private native operations over a resolver-backed access. Per-call
     * version and capability gating still applies through {@link #notifyStatus}.
     */
    public static StatusToolbarAdapter connectedVerifiedCx(
        final String hostVersion,
        final CxStatusBarHostAccess access
    ) {
        return connected(new CxStatusBarHostOperations(hostVersion, access));
    }

    /**
     * @return an adapter with no host behind it; every call returns an
     *     {@link SafeModeDiagnostic.Code#ADAPTER_UNAVAILABLE} diagnostic
     */
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
                statusVersionDiagnostic(capability, operations.hostVersion());
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

    private static Optional<SafeModeDiagnostic> statusVersionDiagnostic(
        final Capability capability,
        final String hostVersion
    ) {
        return HostUiVersionCheck.diagnosticFor(capability.id(), hostVersion);
    }

    private static <T> Supplier<AdapterResult<T>> unavailable(final Capability capability) {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(capability.id()));
    }

    @FunctionalInterface
    private interface HostCall<T> {
        T invoke(HostOperations operations);
    }
}
