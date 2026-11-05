package dev.turboism.compatibility.policy;

import java.util.Objects;
import java.util.Set;

/**
 * Host-independent selector admission decision for exact version, capability and alias evidence.
 *
 * <p>The predicate is deliberately evidence-driven: it never consults a host, a resolver or
 * a record store. A caller supplies the selector requirement data plus the evidence already
 * observed for the running host, and the decision is an exact set membership check. Missing
 * evidence, partial records and unreviewed future versions all fail closed.</p>
 */
public final class SelectorAdmissionPolicy {

    private SelectorAdmissionPolicy() {}

    /**
     * Decides whether exact version, capability id and alias requirements admit the evidence.
     *
     * <p>Every evidence input is required. A {@code null} version, capability set or alias set is
     * treated as absent evidence and refuses admission rather than throwing, so a partially
     * verified record can never widen support.</p>
     *
     * @param supportedVersions exact versions the selector contract supports
     * @param capabilityId the capability id the bound record must carry
     * @param requiredAliases every selector alias the bound record must have verified
     * @param version the exact host version declared at probe time
     * @param capabilities the capability ids the bound record admitted
     * @param availableAliases the selector aliases the bound record verified
     * @return {@code true} only when the exact version is supported and every capability and
     *     alias requirement is present
     * @throws NullPointerException when {@code supportedVersions}, {@code capabilityId} or
     *     {@code requiredAliases} is {@code null}
     */
    public static boolean admits(
            final Set<String> supportedVersions,
            final String capabilityId,
            final Set<String> requiredAliases,
            final String version,
            final Set<String> capabilities,
            final Set<String> availableAliases) {
        Objects.requireNonNull(supportedVersions, "supportedVersions");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(requiredAliases, "requiredAliases");
        return version != null
                && capabilities != null
                && availableAliases != null
                && supportedVersions.contains(version)
                && capabilities.contains(capabilityId)
                && availableAliases.containsAll(requiredAliases);
    }
}
