package dev.turboism.adapter.ui;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether the running Cubism host version is inside the scope a {@code ui.*} capability
 * has been reviewed against.
 *
 * <p>Admission is exact rather than range-based: the running version must name a reviewed host
 * artifact for the capability. This is a static utility and cannot be instantiated.</p>
 */
public final class HostUiVersionCheck {

    /** Exact Cubism versions whose host artifacts have been reviewed. */
    public static final Set<String> REVIEWED_HOST_VERSIONS = Set.of("5.2.03", "5.3.02");

    public static final String STATUS_NOTIFY_CAPABILITY_ID = "ui.status.notify";

    /**
     * Capability-specific exceptions to the default exact-version admission policy.
     *
     * <p>The default admits both reviewed artifacts. A capability belongs here only when its
     * reviewed records support a strict subset of those hosts. This structure intentionally cannot
     * admit a patch version merely because it falls inside the same minor-version line.</p>
     */
    private static final Map<String, Set<String>> CAPABILITY_VERSION_OVERRIDES = Map.of();

    private HostUiVersionCheck() {
    }

    /**
     * Checks {@code hostVersion} against the exact versions reviewed for {@code capabilityId}.
     *
     * <p>Unknown capability IDs use the default reviewed-host set; capability-specific evidence can
     * narrow that set through {@link #CAPABILITY_VERSION_OVERRIDES}. Version ranges are deliberately
     * not parsed here: an unreviewed patch such as 5.3.03 must fail closed rather than inherit the
     * evidence for 5.3.02.</p>
     *
     * @param capabilityId capability being guarded
     * @param hostVersion exact version string reported by the host
     * @return empty when the exact host version is reviewed, otherwise a
     *     {@link SafeModeDiagnostic.Code#HOST_VERSION_UNSUPPORTED} diagnostic
     * @throws NullPointerException if either argument is null
     */
    public static Optional<SafeModeDiagnostic> diagnosticFor(
        final String capabilityId,
        final String hostVersion
    ) {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(hostVersion, "hostVersion");
        final Set<String> reviewedVersions = CAPABILITY_VERSION_OVERRIDES.getOrDefault(
            capabilityId,
            REVIEWED_HOST_VERSIONS
        );
        if (reviewedVersions.contains(hostVersion)) {
            return Optional.empty();
        }
        return Optional.of(SafeModeDiagnostic.hostVersionUnsupported(capabilityId, hostVersion));
    }

    /** @deprecated pass the affected capability ID explicitly. */
    @Deprecated
    public static Optional<SafeModeDiagnostic> diagnosticFor(final String hostVersion) {
        return diagnosticFor("adapter.host", hostVersion);
    }
}
