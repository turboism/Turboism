package dev.turboism.adapter.cubism.optimization;

import dev.turboism.compatibility.policy.ReviewedCubismReleases;
import dev.turboism.mapping.verification.CubismEditorReleaseDetector;
import dev.turboism.mapping.verification.CubismHostIdentity;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.HostIdentityProbe;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Per-hook reviewed-contract binding: which host classes a bytecode hook may touch, and on
 * which declared Cubism generation.
 *
 * <p>A hook contributes one {@link Candidate} per reviewed generation: the profile or target
 * record to bind plus the internal names of every host class the hook's contract touches.
 * {@link #resolve} probes the actual artifact for its declared product/version/build and then
 * binds a candidate only when its target classes exist in the artifact.</p>
 *
 * <p>Identity is the external trust boundary: a reviewed release is admitted by its
 * whole-artifact digest in {@link ReviewedHostArtifacts}, and an unreviewed declaration is
 * admitted only through its own self-consistent Cubism identity. Class bytes are never
 * re-digested here — the weave gates verify the exact method shapes they rewrite, so a
 * changed body refuses that hook alone without a per-class pin table.</p>
 *
 * <p>A declaration is mandatory: target classes never substitute for product and version
 * identity. {@code UNREADABLE}, {@code NOT_CUBISM}, {@code DECLARATION_MISSING},
 * {@code DECLARATION_MALFORMED} and {@code DECLARATION_AMBIGUOUS} probes refuse before any
 * class is inspected, so a premain hook can never patch a host whose identity the runtime
 * itself would reject.</p>
 *
 * <p>The observed artifact identity is never rewritten to a reviewed digest: the bound
 * {@link Bound#sourceVersion()} is the reviewed generation the contract was taken from,
 * {@link Bound#probe()} keeps the artifact's own digest, and callers keep passing the real
 * artifact path to the transformers so {@code CodeSource} attestation still binds to the
 * loaded host.</p>
 */
public final class ReviewedHostContract {

    private ReviewedHostContract() {}

    /**
     * One reviewed class method, identified by the constant-pool-independent shape captured
     * by {@link ReviewedMethodShape#read}.
     *
     * @param owner internal class name, such as {@code "com/live2d/cubism/foo"}
     * @param method method name
     * @param descriptor JVM method descriptor
     * @param expectedShape reviewed semantic instructions, operands, branches and handlers
     */
    public record MethodShapeSpec(String owner, String method, String descriptor, List<String> expectedShape) {
        public MethodShapeSpec {
            owner = requireText(owner, "owner");
            method = requireText(method, "method");
            descriptor = requireText(descriptor, "descriptor");
            expectedShape = List.copyOf(Objects.requireNonNull(expectedShape, "expectedShape"));
            if (expectedShape.isEmpty()) {
                throw new IllegalArgumentException("expectedShape must not be empty");
            }
        }

        private static String requireText(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    /**
     * One reviewed generation of a hook contract.
     *
     * @param version reviewed Cubism version this contract was recovered from
     * @param contract the profile/target object the hook installs with
     * @param targetClasses internal names of every host class the hook rewrites or reads
     * @param methodShapes reviewed target-method shapes that gate the weave on an unreviewed
     *     build; empty keeps the declared-release rule
     */
    public record Candidate<T>(
            String version, T contract, List<String> targetClasses, List<MethodShapeSpec> methodShapes) {
        public Candidate {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(contract, "contract");
            targetClasses = List.copyOf(Objects.requireNonNull(targetClasses, "targetClasses"));
            methodShapes = List.copyOf(Objects.requireNonNull(methodShapes, "methodShapes"));
            if (targetClasses.isEmpty()) {
                throw new IllegalArgumentException("a candidate must name at least one target class");
            }
            for (final MethodShapeSpec shape : methodShapes) {
                if (!targetClasses.contains(shape.owner())) {
                    throw new IllegalArgumentException(
                            "method shape owner " + shape.owner() + " is not a declared target class");
                }
            }
        }

        /** A candidate that binds on its declared generation alone. */
        public Candidate(final String version, final T contract, final List<String> targetClasses) {
            this(version, contract, targetClasses, List.of());
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
     * @param matchedVersions every candidate version whose target classes were present
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
     * Builds one candidate per reviewed version from a version-keyed target-class table.
     *
     * @param targets version to declared target internal names
     * @param contractFor contract factory for each version
     * @return candidates in the table's iteration order
     */
    public static <T> List<Candidate<T>> candidates(
            final Map<String, List<String>> targets, final Function<String, T> contractFor) {
        return candidates(targets, contractFor, version -> List.of());
    }

    /**
     * Builds one candidate per reviewed version with optional method-shape evidence.
     *
     * @param targets version to declared target internal names
     * @param contractFor contract factory for each version
     * @param methodShapesFor method-shape factory for each version; must not return null
     * @return candidates in the table's iteration order
     */
    public static <T> List<Candidate<T>> candidates(
            final Map<String, List<String>> targets,
            final Function<String, T> contractFor,
            final Function<String, List<MethodShapeSpec>> methodShapesFor) {
        final List<Candidate<T>> candidates = new ArrayList<>(targets.size());
        for (final Map.Entry<String, List<String>> entry : targets.entrySet()) {
            final T contract = contractFor.apply(entry.getKey());
            if (contract != null) {
                candidates.add(new Candidate<>(
                        entry.getKey(),
                        contract,
                        entry.getValue(),
                        Objects.requireNonNull(methodShapesFor.apply(entry.getKey()), "methodShapesFor result")));
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
            final String failed = firstMissingTarget(jar, candidate.targetClasses());
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
        final List<String> mismatch = new ArrayList<>();
        for (final Candidate<T> candidate : candidates) {
            final String missing = firstMissingTarget(jar, candidate.targetClasses());
            if (missing != null) {
                mismatch.add(candidate.version() + ":" + missing);
                continue;
            }
            // An unreviewed declaration must also prove every method shape it declares, so a
            // changed hook body refuses that hook alone instead of riding the class names.
            final String shapeFailed = firstMethodShapeMismatch(jar, candidate);
            if (shapeFailed == null) {
                matched.add(candidate);
            } else {
                mismatch.add(candidate.version() + ":" + shapeFailed);
            }
        }
        // Candidates that name different target sets cannot merge; equal contracts
        // behind the same evidence describe one bound feature, so they merge.
        final List<Candidate<T>> distinct = new ArrayList<>();
        for (final Candidate<T> candidate : matched) {
            final boolean duplicate = distinct.stream()
                    .anyMatch(other -> Objects.equals(other.contract(), candidate.contract())
                            && other.targetClasses().equals(candidate.targetClasses()));
            if (!duplicate) {
                distinct.add(candidate);
            }
        }
        if (distinct.isEmpty()) {
            return new Refused<>("no reviewed target contract matched the host classes"
                    + (mismatch.isEmpty() ? "" : ": " + String.join(", ", mismatch)));
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
            // An unreviewed patch on the same minor line is represented by its
            // reviewed sibling rather than an arbitrary older generation.
            for (final Candidate<T> candidate : matched) {
                if (sameMinorLine(candidate.version(), declared)) {
                    return candidate;
                }
            }
        }
        return matched.get(0);
    }

    private static boolean sameMinorLine(final String left, final String right) {
        return minorLine(left).equals(minorLine(right));
    }

    private static String minorLine(final String version) {
        final int first = version.indexOf('.');
        if (first < 0) return version;
        final int second = version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
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
     * Verifies that a class presented to a transformer belongs to the bound hook's contract.
     *
     * <p>An empty declaration means the caller established admission by another route — an
     * isolated bytecode fixture or a hook that scopes owners itself — so it never narrows.</p>
     *
     * @param targets internal names of the bound contract's target classes
     * @param owner internal class name supplied by the defining loader
     * @return whether this owner is one the reviewed contract names, or the contract names none
     */
    public static boolean declaresTarget(final Set<String> targets, final String owner) {
        return targets.isEmpty() || (owner != null && targets.contains(owner));
    }

    /**
     * The first declared target class the artifact lacks, or {@code null} when every entry exists.
     */
    private static String firstMissingTarget(final JarFile jar, final List<String> targets) {
        for (final String owner : targets) {
            if (jar.getJarEntry(owner + ".class") == null) {
                return owner + " absent";
            }
        }
        return null;
    }

    /**
     * Verifies the candidate's reviewed method shapes. Every declared shape must match, so an
     * unreviewed build is admitted only when the exact bodies the hook rewrites are identical.
     * A candidate that declares no shape binds on its target classes alone.
     */
    private static String firstMethodShapeMismatch(final JarFile jar, final Candidate<?> candidate) {
        final List<MethodShapeSpec> shapes = candidate.methodShapes();
        if (shapes.isEmpty()) {
            return null;
        }
        for (final MethodShapeSpec shape : shapes) {
            final byte[] bytes;
            try {
                final JarEntry entry = jar.getJarEntry(shape.owner() + ".class");
                if (entry == null) {
                    return shape.owner() + " absent";
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
            } catch (IOException | RuntimeException failure) {
                return shape.owner() + " method shape unreadable";
            }
            final List<String> observed;
            try {
                observed = ReviewedMethodShape.read(bytes, shape.owner(), shape.method(), shape.descriptor());
            } catch (RuntimeException failure) {
                return shape.owner() + " method shape unreadable";
            }
            if (!shape.expectedShape().equals(observed)) {
                return shape.owner() + "#" + shape.method() + shape.descriptor() + " shape mismatch";
            }
        }
        return null;
    }
}
