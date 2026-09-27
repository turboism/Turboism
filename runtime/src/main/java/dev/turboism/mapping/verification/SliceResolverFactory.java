package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shared contract implemented by every {@code Verified*ResolverFactory} so the
 * adapter connector can dispatch exact and compatibility-bound slices through
 * one seam.
 *
 * <p>{@link #create} keeps the exact semantics: reviewed record, reviewed
 * artifact fingerprint, pinned manifest. {@link #createCompatible} binds the
 * same pinned record to a structurally verified but unreviewed artifact under
 * an admitted {@link SliceContract}; it never widens the record and never
 * claims reviewed-host status.</p>
 */
public interface SliceResolverFactory {

    /**
     * Creates a resolver for a byte-identical reviewed host.
     *
     * @param reviewedRecord pinned verification record
     * @param verifiedArtifact artifact whose digest matches the record
     * @param hostClassLoader defining classloader
     * @return the verified member resolver
     * @throws IOException if the record or artifact cannot be read
     */
    VerifiedMemberResolver create(
        Path reviewedRecord,
        Path verifiedArtifact,
        ClassLoader hostClassLoader
    ) throws IOException;

    /**
     * Creates a resolver under an admitted compatibility contract.
     *
     * @param reviewedRecord catalog-pinned verification record
     * @param hostArtifact structurally verified host artifact
     * @param hostClassLoader defining classloader
     * @param contract admitted slice contract
     * @return the verified member resolver
     * @throws IOException if the record or artifact cannot be read
     */
    VerifiedMemberResolver createCompatible(
        Path reviewedRecord,
        Path hostArtifact,
        ClassLoader hostClassLoader,
        SliceContract contract
    ) throws IOException;
}
