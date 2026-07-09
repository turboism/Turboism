package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class MainToolbarAdapterImpl implements MainToolbarAdapter {

    private final Optional<HostOperations> host;

    private MainToolbarAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public static MainToolbarAdapter connected(final HostOperations host) {
        return new MainToolbarAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    public static MainToolbarAdapter safeMode() {
        return new MainToolbarAdapterImpl(Optional.empty());
    }

    @Override
    public AdapterResult<Registration> contributeMainToolbar(
        final MainToolbarRegistry.MainToolbarContribution contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        return host.map(operations -> callIfSupported(operations, contribution))
            .orElseGet(unavailable());
    }

    private AdapterResult<Registration> callIfSupported(
        final HostOperations operations,
        final MainToolbarRegistry.MainToolbarContribution contribution
    ) {
        try {
            final Optional<SafeModeDiagnostic> versionDiagnostic = HostUiVersionCheck.diagnosticFor(operations.hostVersion());
            if (versionDiagnostic.isPresent()) {
                return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
            }
            if (!operations.supports(Capability.MAIN_TOOLBAR_CONTRIBUTE)) {
                return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(Capability.MAIN_TOOLBAR_CONTRIBUTE.id()));
            }
            return AdapterResult.available(operations.contributeMainToolbar(contribution));
        } catch (AdapterHostException exception) {
            return AdapterResult.unavailable(exception.diagnostic());
        }
    }

    private static Supplier<AdapterResult<Registration>> unavailable() {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(Capability.MAIN_TOOLBAR_CONTRIBUTE.id()));
    }
}
