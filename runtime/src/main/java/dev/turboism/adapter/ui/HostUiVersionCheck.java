package dev.turboism.adapter.ui;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;

import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether the running Cubism host version is inside the scope a {@code ui.*} capability
 * has been reviewed against.
 *
 * <p>Most UI capabilities are admitted only on the 5.3 line; {@code ui.status.notify} is the one
 * capability with exact-version evidence on both admitted hosts (5.2.03 and 5.3.02) and so uses a
 * wider scope. This is a static utility and cannot be instantiated.</p>
 */
public final class HostUiVersionCheck {

    public static final String HOST_VERSION_SCOPE = "[5.3.0,5.4.0)";

    /**
     * Reviewed exact-version status slice: 5.2.03 and 5.3.02 both live in
     * [5.2.0,5.4.0). Every other ui.* capability stays on the 5.3 scope until
     * its own exact-version record exists.
     */
    public static final String STATUS_NOTIFY_VERSION_SCOPE = "[5.2.0,5.4.0)";
    public static final String STATUS_NOTIFY_CAPABILITY_ID = "ui.status.notify";

    /**
     * Capabilities that carry a reviewed record for <em>both</em> admitted Cubism versions and are
     * therefore checked against the wider scope.
     *
     * <p>The default scope only covers the 5.3 line, so a capability that gains 5.2.03 evidence
     * but is not listed here fails this gate before its adapter is ever called and returns an
     * empty result that is indistinguishable from "the model has none". Exact-host validation
     * caught clip mask in exactly that state: its 5.2.03 record was admitted and its selectors
     * verified, yet the read never ran.</p>
     */
    private static final java.util.Set<String> BOTH_VERSION_CAPABILITY_IDS = java.util.Set.of(
        STATUS_NOTIFY_CAPABILITY_ID,
        "cubism.clipmask.read"
    );

    private static final VersionRange SUPPORTED_RANGE = VersionRange.parse(HOST_VERSION_SCOPE);
    private static final VersionRange STATUS_NOTIFY_RANGE =
        VersionRange.parse(STATUS_NOTIFY_VERSION_SCOPE);

    private HostUiVersionCheck() {
    }

    /**
     * Checks {@code hostVersion} against the scope reviewed for {@code capabilityId}.
     *
     * <p>An unparsable version string is treated exactly like an out-of-scope one: no exception
     * escapes, the caller simply receives the unsupported diagnostic.</p>
     *
     * @param capabilityId capability being guarded; selects the wider status-notify scope when it
     *     equals {@link #STATUS_NOTIFY_CAPABILITY_ID}
     * @param hostVersion version string reported by the host
     * @return empty when the host is in scope, otherwise a
     *     {@link SafeModeDiagnostic.Code#HOST_VERSION_UNSUPPORTED} diagnostic
     * @throws NullPointerException if either argument is null
     */
    public static Optional<SafeModeDiagnostic> diagnosticFor(
        final String capabilityId,
        final String hostVersion
    ) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(hostVersion, "hostVersion");
        try {
            final PluginVersion version = PluginVersion.parse(hostVersion);
            final VersionRange range = BOTH_VERSION_CAPABILITY_IDS.contains(capabilityId)
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
