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

    public static Optional<SafeModeDiagnostic> diagnosticFor(
        final String capabilityId,
        final String hostVersion
    ) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(hostVersion, "hostVersion");
        try {
            if (SUPPORTED_RANGE.contains(PluginVersion.parse(hostVersion))) {
                return Optional.empty();
            }
        } catch (IllegalArgumentException ignored) {
            return Optional.of(SafeModeDiagnostic.hostVersionUnsupported(capabilityId, hostVersion));
        }
        return Optional.of(SafeModeDiagnostic.hostVersionUnsupported(capabilityId, hostVersion));
    }

    /** @deprecated pass the affected capability ID explicitly. */
    @Deprecated
    public static Optional<SafeModeDiagnostic> diagnosticFor(final String hostVersion) {
        return diagnosticFor("adapter.host", hostVersion);
    }
}
