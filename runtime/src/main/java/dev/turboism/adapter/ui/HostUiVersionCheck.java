package dev.turboism.adapter.ui;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;

import java.util.Objects;
import java.util.Optional;

public final class HostUiVersionCheck {

    public static final String HOST_VERSION_SCOPE = "[5.3.0,5.4.0)";

    /**
     * Reviewed exact-version status slice: 5.2.03 and 5.3.02 both live in
     * [5.2.0,5.4.0). Every other ui.* capability stays on the 5.3 scope until
     * its own exact-version record exists.
     */
    public static final String STATUS_NOTIFY_VERSION_SCOPE = "[5.2.0,5.4.0)";
    public static final String STATUS_NOTIFY_CAPABILITY_ID = "ui.status.notify";

    private static final VersionRange SUPPORTED_RANGE = VersionRange.parse(HOST_VERSION_SCOPE);
    private static final VersionRange STATUS_NOTIFY_RANGE =
        VersionRange.parse(STATUS_NOTIFY_VERSION_SCOPE);

    private HostUiVersionCheck() {
    }

    public static Optional<SafeModeDiagnostic> diagnosticFor(
        final String capabilityId,
        final String hostVersion
    ) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(hostVersion, "hostVersion");
        try {
            final PluginVersion version = PluginVersion.parse(hostVersion);
            final VersionRange range = STATUS_NOTIFY_CAPABILITY_ID.equals(capabilityId)
                ? STATUS_NOTIFY_RANGE
                : SUPPORTED_RANGE;
            if (range.contains(version)) {
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
