package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class UiSurfaceAdapterImpl implements UiSurfaceAdapter {

    private final Optional<HostOperations> host;

    private UiSurfaceAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public static UiSurfaceAdapter connected(final HostOperations host) {
        return new UiSurfaceAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    public static UiSurfaceAdapter safeMode() {
        return new UiSurfaceAdapterImpl(Optional.empty());
    }

    @Override
    public AdapterResult<Registration> contributeOverlay(final OverlayContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        return withCapability(Capability.OVERLAY_CONTRIBUTE, operations -> operations.contributeOverlay(contribution));
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
    public AdapterResult<Registration> contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        return withCapability(Capability.PANEL_CONTRIBUTE, operations -> operations.contributeEmbeddedPanel(contribution));
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
