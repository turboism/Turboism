package dev.turboism.adapter.ui;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;

import java.util.Objects;
import java.util.Optional;

public final class HostUiVersionCheck {

    public static final String HOST_VERSION_SCOPE = "[5.3.0,5.4.0)";

    private static final VersionRange SUPPORTED_RANGE = VersionRange.parse(HOST_VERSION_SCOPE);

    private HostUiVersionCheck() {
    }

    static Optional<SafeModeDiagnostic> diagnosticFor(final String hostVersion) {
        Objects.requireNonNull(hostVersion, "hostVersion");
        try {
            if (SUPPORTED_RANGE.contains(PluginVersion.parse(hostVersion))) {
                return Optional.empty();
            }
        } catch (IllegalArgumentException ignored) {
            return Optional.of(SafeModeDiagnostic.hostVersionUnsupported(hostVersion));
        }
        return Optional.of(SafeModeDiagnostic.hostVersionUnsupported(hostVersion));
    }
}
