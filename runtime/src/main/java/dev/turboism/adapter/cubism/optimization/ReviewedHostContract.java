package dev.turboism.adapter.cubism.optimization;

import dev.turboism.mapping.verification.CubismEditorReleaseDetector;
import dev.turboism.mapping.verification.CubismHostIdentity;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.HostIdentityProbe;
import dev.turboism.mapping.verification.ReviewedCubismReleases;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Per-hook reviewed-contract binding: the mechanism that replaces whole-artifact
 * digest admission for the bytecode hooks.
 *
 * <p>A hook contributes one {@link Candidate} per reviewed generation: the profile or
 * target record to bind plus the SHA-256 of every host class the hook's contract
 * touches, keyed by internal class name. {@link #resolve} probes the actual artifact
 * for its declared product/version/build and then:</p>
 *
 * <ul>
 *   <li>when the declared version+build is a reviewed release, the candidates of that
 *       generation must verify — a repackaged reviewed artifact keeps every feature
 *       whose classes are byte-identical even though the archive digest changed, while
 *       a tampered target class refuses that hook alone;</li>
 *   <li>when the artifact declares a self-consistent Cubism identity whose release is
 *       not reviewed, every candidate is tried; exactly one distinct contract may
 *       match — zero matches or several distinct matching contracts refuse the hook
 *       with a bounded reason.</li>
 * </ul>
 *
 * <p>A declaration is mandatory: class digests never substitute for product and
 * version identity. {@code UNREADABLE}, {@code NOT_CUBISM},
 * {@code DECLARATION_MISSING}, {@code DECLARATION_MALFORMED} and
 * {@code DECLARATION_AMBIGUOUS} probes refuse before any class is inspected, so a
 * premain hook can never patch a host whose identity the runtime itself would
 * reject.</p>
 *
 * <p>The observed artifact identity is never rewritten to a reviewed digest: the bound
 * {@link Bound#sourceVersion()} is the reviewed generation the contract was taken
 * from, {@link Bound#probe()} keeps the artifact's own digest, and callers keep
 * passing the real artifact path to the transformers so {@code CodeSource}
 * attestation still binds to the loaded host.</p>
 */
public final class ReviewedHostContract {

    private ReviewedHostContract() {}

    /**
     * One reviewed generation of a hook contract.
     *
     * @param version reviewed Cubism version this contract was recovered from
     * @param contract the profile/target object the hook installs with
     * @param pinnedClassSha256 SHA-256 of each reviewed class entry, keyed by internal
     *     name (the jar entry path minus {@code .class})
     */
    public record Candidate<T>(String version, T contract, Map<String, String> pinnedClassSha256) {
        public Candidate {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(contract, "contract");
            pinnedClassSha256 = Map.copyOf(Objects.requireNonNull(pinnedClassSha256, "pinnedClassSha256"));
            if (pinnedClassSha256.isEmpty()) {
                throw new IllegalArgumentException("a candidate must pin at least one class");
            }
        }
    }

    /** The outcome of {@link #resolve}: either one bound contract or a refusal. */
    public sealed interface Resolution<T> permits Bound, Refused {}

    /**
     * The single contract the artifact proved.
     *
     * @param sourceVersion reviewed generation the bound contract was recovered from
     *     (the host's declared version only when the host is that generation)
     * @param contract the verified profile/target object
     * @param probe the host identity probe, including the artifact's own digest
     * @param matchedVersions every candidate version whose pins verified
     * @param declaredRelease whether the host declared a reviewed version+build
     */
    public record Bound<T>(
            String sourceVersion,
            T contract,
            HostIdentityProbe probe,
            List<String> matchedVersions,
            boolean declaredRelease)
            implements Resolution<T> {
        public Bound {
            Objects.requireNonNull(sourceVersion, "sourceVersion");
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(probe, "probe");
            matchedVersions = List.copyOf(matchedVersions);
        }

        /**
         * Checks that subsequent reference reads still came from the bound snapshot.
         * Installers call this after preparing their targets and before publishing callbacks.
         *
         * @param artifact the actual host artifact used to prepare the installation
         * @throws IOException if the artifact cannot be read
         * @throws IllegalStateException if it differs from the probed artifact
         */
        public void requireUnchanged(final Path artifact) throws IOException {
            if (!probe.identity().orElseThrow().artifact().equals(HostArtifactDigest.from(artifact))) {
                throw new IllegalStateException("host artifact changed after contract binding");
            }
        }
    }

    /** A fail-closed verdict carrying a bounded diagnostic reason. */
    public record Refused<T>(String reason) implements Resolution<T> {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Builds one candidate per reviewed version from a version-keyed pin table.
     *
     * @param pins version to (internal name to SHA-256) pin maps
     * @param contractFor contract factory for each version
     * @return candidates in the pin table's iteration order
     */
    public static <T> List<Candidate<T>> candidates(
            final Map<String, Map<String, String>> pins, final Function<String, T> contractFor) {
        final List<Candidate<T>> candidates = new ArrayList<>(pins.size());
        for (final Map.Entry<String, Map<String, String>> entry : pins.entrySet()) {
            final T contract = contractFor.apply(entry.getKey());
            if (contract != null) {
                candidates.add(new Candidate<>(entry.getKey(), contract, entry.getValue()));
            }
        }
        return List.copyOf(candidates);
    }

    /** Whether {@link #resolve} bound exactly one contract for the artifact. */
    public static <T> boolean resolved(final Path artifact, final List<Candidate<T>> candidates) {
        return resolve(artifact, candidates) instanceof Bound<T>;
    }

    /**
     * Unwraps a bound contract or throws with the bounded refusal reason.
     * @param feature hook feature name for the exception message
     */
    public static <T> Bound<T> requireBound(final Resolution<T> resolution, final String feature) {
        if (resolution instanceof Bound<T> bound) {
            return bound;
        }
        throw new IllegalArgumentException(feature + " refused: " + ((Refused<T>) resolution).reason());
    }

    /**
     * Binds the actual artifact to a reviewed contract or refuses with a reason.
     *
     * @param artifact the located host artifact (never re-digested into a reviewed one)
     * @param candidates the hook's reviewed contract generations
     * @return the resolution verdict
     */
    public static <T> Resolution<T> resolve(final Path artifact, final List<Candidate<T>> candidates) {
        return resolve(artifact, candidates, CubismEditorReleaseDetector::probe);
    }

    // Identity-probe seam permits deterministic replacement-during-inspection tests.
    static <T> Resolution<T> resolve(
            final Path artifact,
            final List<Candidate<T>> candidates,
            final Function<Path, HostIdentityProbe> identityProbe) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return new Refused<>("no reviewed contract candidates exist");
        }
        final HostIdentityProbe probe = identityProbe.apply(artifact);
        if (!probe.declared()) {
            return new Refused<>("host identity rejected: " + probe.status() + " — " + probe.detail());
        }
        final CubismHostIdentity identity = probe.identity().orElseThrow();
        final boolean declaredRelease = ReviewedCubismReleases.isReviewed(identity.version(), identity.build());
        try {
            final Resolution<T> resolution;
            try (JarFile jar = new JarFile(artifact.toFile())) {
                if (declaredRelease) {
                    final String declared = identity.version();
                    final List<Candidate<T>> own = new ArrayList<>();
                    for (final Candidate<T> candidate : candidates) {
                        if (candidate.version().equals(declared)) {
                            own.add(candidate);
                        }
                    }
                    if (own.isEmpty()) {
                        return new Refused<>("no reviewed target contract for declared release " + declared);
                    }
                    // A reviewed release must prove its own contract, without borrowing
                    // one from another generation when its targets differ or are absent.
                    resolution = bindDeclared(jar, probe, own, true);
                } else {
                    resolution = bindStructurally(jar, probe, candidates, false);
                }
            }
            if (resolution instanceof Bound<T> && !identity.artifact().equals(HostArtifactDigest.from(artifact))) {
                return new Refused<>("host artifact changed during contract inspection");
            }
            return resolution;
        } catch (IOException | RuntimeException failure) {
            return new Refused<>("host artifact could not be inspected: " + failure);
        }
    }

    private static <T> Resolution<T> bindDeclared(
            final JarFile jar,
            final HostIdentityProbe probe,
            final List<Candidate<T>> own,
            final boolean declaredRelease) {
        final List<Candidate<T>> matched = new ArrayList<>();
        final List<String> mismatch = new ArrayList<>();
        for (final Candidate<T> candidate : own) {
            final String failed = firstMismatch(jar, candidate.pinnedClassSha256());
            if (failed == null) {
                matched.add(candidate);
            } else {
                mismatch.add(candidate.version() + ":" + failed);
            }
        }
        if (matched.size() == 1) {
            final Candidate<T> candidate = matched.get(0);
            return new Bound<>(
                    candidate.version(), candidate.contract(), probe, List.of(candidate.version()), declaredRelease);
        }
        if (matched.isEmpty()) {
            return new Refused<>(
                    "declared reviewed release but the target contract is altered: " + String.join(", ", mismatch));
        }
        return new Refused<>("ambiguous reviewed contract binding: " + matched);
    }

    private static <T> Resolution<T> bindStructurally(
            final JarFile jar,
            final HostIdentityProbe probe,
            final List<Candidate<T>> candidates,
            final boolean declaredRelease) {
        final List<Candidate<T>> matched = new ArrayList<>();
        for (final Candidate<T> candidate : candidates) {
            if (firstMismatch(jar, candidate.pinnedClassSha256()) == null) {
                matched.add(candidate);
            }
        }
        // Candidates that pin different class sets cannot merge; equal contracts
        // behind the same evidence describe one bound feature, so they merge.
        final List<Candidate<T>> distinct = new ArrayList<>();
        for (final Candidate<T> candidate : matched) {
            final boolean duplicate = distinct.stream()
                    .anyMatch(other -> Objects.equals(other.contract(), candidate.contract())
                            && other.pinnedClassSha256().equals(candidate.pinnedClassSha256()));
            if (!duplicate) {
                distinct.add(candidate);
            }
        }
        if (distinct.isEmpty()) {
            return new Refused<>("no reviewed target contract matched the host classes");
        }
        if (distinct.size() > 1) {
            return new Refused<>("ambiguous target contract: classes match reviewed generations " + versions(distinct));
        }
        // Every match here is the same contract under equivalent evidence; prefer the
        // candidate whose version the host declared, if any.
        final Candidate<T> bound = preferred(matched, probe);
        return new Bound<>(
                bound.version(),
                bound.contract(),
                probe,
                matched.stream().map(Candidate::version).toList(),
                declaredRelease);
    }

    private static <T> Candidate<T> preferred(final List<Candidate<T>> matched, final HostIdentityProbe probe) {
        final String declared =
                probe.identity().map(CubismHostIdentity::version).orElse(null);
        if (declared != null) {
            for (final Candidate<T> candidate : matched) {
                if (candidate.version().equals(declared)) {
                    return candidate;
                }
            }
        }
        return matched.get(0);
    }

    private static String versions(final List<? extends Candidate<?>> matched) {
        return matched.stream()
                .map(Candidate::version)
                .distinct()
                .sorted()
                .toList()
                .toString();
    }

    /**
     * Verifies bytes supplied by Instrumentation against a bound hook's class pins.
     *
     * @param pins reviewed class hashes of the selected contract
     * @param owner internal class name supplied by the defining loader
     * @param bytes actual class definition presented to the transformer
     * @return whether this owner is pinned and these bytes match that pin
     */
    public static boolean matchesClassBytes(final Map<String, String> pins, final String owner, final byte[] bytes) {
        final String expected = pins.get(owner);
        return expected != null && bytes != null && expected.equals(sha256(bytes));
    }

    /**
     * The first pinned class the artifact violates, or {@code null} when every pinned
     * class entry exists with the reviewed digest.
     */
    private static String firstMismatch(final JarFile jar, final Map<String, String> pins) {
        for (final Map.Entry<String, String> pin : pins.entrySet()) {
            final JarEntry entry = jar.getJarEntry(pin.getKey() + ".class");
            if (entry == null) {
                return pin.getKey() + " absent";
            }
            try (InputStream input = jar.getInputStream(entry)) {
                if (!pin.getValue().equals(sha256(input.readAllBytes()))) {
                    return pin.getKey() + " digest mismatch";
                }
            } catch (IOException | RuntimeException failure) {
                return pin.getKey() + " unreadable";
            }
        }
        return null;
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
