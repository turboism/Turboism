package dev.turboism.adapter.ui;

import dev.turboism.sdk.theme.ThemeStatusSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default {@link ThemeStatusAdapter}: either a live host binding or a permanent safe mode.
 *
 * <p>A read is served only when the host version is in scope for
 * {@link ThemeStatusAdapter#CAPABILITY_ID} and the host reports theme-status reads as supported.
 * Host failures are converted to diagnostics rather than propagated, so callers never see a host
 * exception.</p>
 */
public final class ThemeStatusAdapterImpl implements ThemeStatusAdapter {

    private final Optional<HostOperations> host;

    private ThemeStatusAdapterImpl(final Optional<HostOperations> host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * @param host live theme-status host operations
     * @return an adapter bound to the given host, still subject to per-call version and capability
     *     gating
     * @throws NullPointerException if {@code host} is null
     */
    public static ThemeStatusAdapter connected(final HostOperations host) {
        return new ThemeStatusAdapterImpl(Optional.of(Objects.requireNonNull(host, "host")));
    }

    /**
     * @return an adapter with no host behind it; {@code themeStatus()} always reports
     *     {@link SafeModeDiagnostic.Code#ADAPTER_UNAVAILABLE}
     */
    public static ThemeStatusAdapter safeMode() {
        return new ThemeStatusAdapterImpl(Optional.empty());
    }

    @Override
    public AdapterResult<Optional<ThemeStatusSnapshot>> themeStatus() {
        return host.map(this::callIfSupported).orElseGet(unavailable());
    }

    private AdapterResult<Optional<ThemeStatusSnapshot>> callIfSupported(final HostOperations operations) {
        try {
            final Optional<SafeModeDiagnostic> versionDiagnostic =
                HostUiVersionCheck.diagnosticFor(CAPABILITY_ID, operations.hostVersion());
            if (versionDiagnostic.isPresent()) {
                return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
            }
            if (!operations.supportsThemeStatusRead()) {
                return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(CAPABILITY_ID));
            }
            return AdapterResult.available(operations.themeStatus());
        } catch (AdapterHostException exception) {
            return AdapterResult.unavailable(exception.diagnostic());
        } catch (RuntimeException exception) {
            return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                CAPABILITY_ID,
                "Host theme-status adapter call failed safely."
            ));
        }
    }

    private static Supplier<AdapterResult<Optional<ThemeStatusSnapshot>>> unavailable() {
        return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(CAPABILITY_ID));
    }
}
