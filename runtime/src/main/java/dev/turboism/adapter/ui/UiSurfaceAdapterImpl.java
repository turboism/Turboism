package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default {@link UiSurfaceAdapter}: either a live host binding or a permanent safe mode.
 *
 * <p>Dialog and file-chooser calls are gated per capability on host version and host support, and
 * every host failure is converted into a diagnostic -- an {@link AdapterHostException} into its own
 * diagnostic, any other {@link RuntimeException} into a validation failure -- so no host exception
 * reaches the caller.</p>
 */
public final class UiSurfaceAdapterImpl implements UiSurfaceAdapter {

    private final Optional<HostOperations> host;

    private UiSurfaceAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * @param host live UI host operations
     * @return an adapter bound to the given host, still subject to per-call version and capability
     *     gating
     * @throws NullPointerException if {@code host} is null
     */
    public static UiSurfaceAdapter connected(final HostOperations host) {
        return new UiSurfaceAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    /**
     * @return an adapter with no host behind it; every operation reports
     *     {@link SafeModeDiagnostic.Code#ADAPTER_UNAVAILABLE} for its capability
     */
    public static UiSurfaceAdapter safeMode() {
        return new UiSurfaceAdapterImpl(Optional.empty());
    }

    @Override
    public AdapterResult<Registration> openDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        return withCapability(Capability.DIALOG_CONTRIBUTE, operations -> operations.openDialog(request));
    }

    @Override
    public AdapterResult<Boolean> confirmDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        return withCapability(Capability.DIALOG_CONTRIBUTE, operations -> operations.confirmDialog(request));
    }

    @Override
    public AdapterResult<Optional<String>> requestFile(final FileChooserRequest request) {
        Objects.requireNonNull(request, "request");
        return withCapability(Capability.FILE_CHOOSER_REQUEST, operations -> operations.requestFile(request));
    }

    private <T> AdapterResult<T> withCapability(
        final Capability capability,
        final Function<HostOperations, T> hostCall
    ) {
        return host.map(operations -> callIfSupported(operations, capability, hostCall))
            .orElseGet(unavailable(capability));
    }

    private <T> AdapterResult<T> callIfSupported(
        final HostOperations operations,
        final Capability capability,
        final Function<HostOperations, T> hostCall
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
            return AdapterResult.available(hostCall.apply(operations));
        } catch (AdapterHostException exception) {
            return AdapterResult.unavailable(exception.diagnostic());
        } catch (RuntimeException exception) {
            return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                capability.id(),
                "Host UI adapter call failed safely."
            ));
        }
    }

    private static <T> Supplier<AdapterResult<T>> unavailable(final Capability capability) {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(capability.id()));
    }
}
