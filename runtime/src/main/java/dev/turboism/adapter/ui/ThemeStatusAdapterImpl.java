package dev.turboism.adapter.ui;

import dev.turboism.sdk.theme.ThemeStatusSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ThemeStatusAdapterImpl implements ThemeStatusAdapter {

    private final Optional<HostOperations> host;

    private ThemeStatusAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public static ThemeStatusAdapter connected(final HostOperations host) {
        return new ThemeStatusAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    public static ThemeStatusAdapter safeMode() {
        return new ThemeStatusAdapterImpl(Optional.empty());
    }

    @Override
    public AdapterResult<Optional<ThemeStatusSnapshot>> themeStatus() {
        return host.map(this::callIfSupported).orElseGet(unavailable());
    }

    private AdapterResult<Optional<ThemeStatusSnapshot>> callIfSupported(final HostOperations operations) {
        final Optional<SafeModeDiagnostic> versionDiagnostic = HostUiVersionCheck.diagnosticFor(operations.hostVersion());
        if (versionDiagnostic.isPresent()) {
            return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
        }
        if (!operations.supportsThemeStatusRead()) {
            return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(CAPABILITY_ID));
        }
        return AdapterResult.available(operations.themeStatus());
    }

    private static Supplier<AdapterResult<Optional<ThemeStatusSnapshot>>> unavailable() {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(CAPABILITY_ID));
    }
}
