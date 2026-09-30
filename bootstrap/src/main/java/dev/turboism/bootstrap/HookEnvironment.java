package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.CompatibilityResolution;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.preview.PreviewRuntime;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything an install-time hook can legitimately see: instrumentation, the
 * located host (once resolved), the preview runtime (once started), agent
 * options, and the admission facts the agent has already established.
 *
 * <p>The agent supplies shared runtime and slice admission. A contributor may
 * additionally prove its own target contract, but cannot widen that admission.
 * Premain hooks inspect their targets before a runtime verdict exists and defer
 * runtime-dependent capability readiness until binding.</p>
 */
final class HookEnvironment {

    private final Instrumentation instrumentation;
    private final AgentOptions options;
    private final HostClassLocator.LocatedHost host;
    private final PreviewRuntime runtime;
    private final String profile;
    private final CompatibilityResolution hostResolution;
    private final boolean fullRuntimeAdmission;
    private final boolean safeMode;
    private final Path verificationDirectory;
    private final String classPath;
    private final Path workingDirectory;
    private final RuntimeStartupConfig startupPolicy;

    private HookEnvironment(final Builder builder) {
        this.instrumentation = builder.instrumentation;
        this.options = builder.options;
        this.host = builder.host;
        this.runtime = builder.runtime;
        this.profile = builder.profile;
        this.hostResolution = builder.hostResolution;
        this.fullRuntimeAdmission = builder.fullRuntimeAdmission;
        this.safeMode = builder.safeMode;
        this.verificationDirectory = builder.verificationDirectory;
        this.classPath = builder.classPath;
        this.workingDirectory = builder.workingDirectory;
        this.startupPolicy = builder.startupPolicy;
    }

    Instrumentation instrumentation() {
        return instrumentation;
    }

    /**
     * @return the resolved agent options; package-visible because the option
     *     record is an agent internal
     */
    AgentOptions options() {
        return options;
    }

    /**
     * @return the located host, present from {@link HookContributor.Phase#HOST_RESOLVED}
     *     onward
     */
    Optional<HostClassLocator.LocatedHost> host() {
        return Optional.ofNullable(host);
    }

    /**
     * @return the started preview runtime, present only in
     *     {@link HookContributor.Phase#RUNTIME_STARTED}
     */
    Optional<PreviewRuntime> runtime() {
        return Optional.ofNullable(runtime);
    }

    /**
     * @return the reviewed Cubism version profile of the located host, or
     *     {@code null} before the host is resolved
     */
    String profile() {
        return profile;
    }

    /**
     * @return whether the located host passed full-runtime admission
     */
    boolean fullRuntimeAdmission() {
        return fullRuntimeAdmission;
    }

    /**
     * The compatibility resolution that admitted this host, when the launcher
     * computed one. Hooks must read admission from here instead of deriving
     * eligibility from artifact digests themselves.
     *
     * @return the shared admission verdict, empty before host resolution
     */
    Optional<CompatibilityResolution> hostResolution() {
        return Optional.ofNullable(hostResolution);
    }

    /**
     * Whether the resolved admission granted a catalog slice. Individual optional
     * capabilities may still be unavailable; consumers must check their own
     * capability evidence and hooks must prove their target contracts.
     *
     * @param sliceId catalog slice key such as {@code "editor-model"}
     * @return {@code true} only when the slice was admitted
     */
    boolean sliceAdmitted(final String sliceId) {
        return hostResolution != null && hostResolution.slice(sliceId).admitted();
    }

    /**
     * Allows a runtime hook to attempt its own target proof for one admitted slice.
     * Contributors using this gate must verify actual transformation and withdraw
     * dependent capabilities on failure before plugin initialization.
     */
    boolean runtimeSliceAdmitted(final String sliceId) {
        return hostResolution == null
                ? ordinaryReviewedRuntimeAdmitted()
                : hostResolution.runtimeAdmitted() && sliceAdmitted(sliceId);
    }

    /**
     * @return whether the runtime is both reviewed-admitted and full-runtime admitted
     *     for the located host profile
     */
    boolean ordinaryReviewedRuntimeAdmitted() {
        return fullRuntimeAdmission && profile != null && ReviewedHostArtifacts.admitsFullRuntime(profile);
    }

    /**
     * Whether the compatibility resolution bound every admitted slice to the
     * host's declared reviewed generation. Only a repacked artifact whose
     * declared identity is a reviewed runtime generation — and whose entire
     * selector surface verified against that generation's records — reaches
     * {@code true}; unknown declared versions and partially bound resolutions
     * stay {@code false}.
     *
     * @return whether runtime hooks may treat the host as its declared generation
     */
    boolean declaredGenerationBound() {
        return hostResolution != null && hostResolution.declaredGenerationBound();
    }

    /**
     * The gate runtime-phase hooks install under: byte-exact reviewed hosts as
     * before, plus compatibility hosts whose Editor-model contract bound the
     * declared reviewed generation. Independent Core and optional UI bindings do not
     * change that Editor contract. Hooks still verify their own selectors at
     * install time — this gate only decides whether they are attempted at all.
     *
     * @return whether runtime-installed hooks may run on this host
     */
    boolean hookRuntimeAdmitted() {
        return ordinaryReviewedRuntimeAdmitted()
                || (hostResolution != null
                        && hostResolution.runtimeAdmitted()
                        && hostResolution
                                .contractFor("editor-model")
                                .map(dev.turboism.mapping.verification.SliceContract::declaredGenerationBound)
                                .orElse(false));
    }

    /**
     * The reviewed generation runtime hooks bind profiles to: the declared
     * version on exact hosts, or the declared reviewed generation a compatible
     * host fully bound. Empty on compatibility sessions bound to foreign or
     * unreviewed generations — digest-keyed profiles must not answer those.
     *
     * @return the generation to resolve hook profiles by, or empty
     */
    Optional<String> admittedRuntimeGeneration() {
        if (ordinaryReviewedRuntimeAdmitted()) {
            return Optional.ofNullable(profile);
        }
        if (hookRuntimeAdmitted()) {
            return hostResolution
                    .contractFor("editor-model")
                    .map(dev.turboism.mapping.verification.SliceContract::sourceVersion);
        }
        return Optional.empty();
    }

    /**
     * Creates one slice's member resolver honouring the admission mode: the
     * exact reviewed path keeps its byte-pinned manifest, while a
     * compatibility-bound slice re-binds the catalog-pinned record through its
     * {@link dev.turboism.mapping.verification.SliceContract}.
     *
     * @param factory the slice's resolver factory
     * @param recordFileName record to use when no compatibility contract exists
     * @param sliceId the catalog slice key the resolver belongs to
     * @return the verified member resolver for this slice
     * @throws java.io.IOException when the record cannot be extracted or verified
     */
    dev.turboism.mapping.verification.VerifiedMemberResolver sliceResolver(
            final dev.turboism.mapping.verification.SliceResolverFactory factory,
            final String recordFileName,
            final String sliceId)
            throws java.io.IOException {
        final var located = host().orElseThrow();
        final var contract = hostResolution != null
                ? hostResolution.contractFor(sliceId)
                : java.util.Optional.<dev.turboism.mapping.verification.SliceContract>empty();
        if (contract.isPresent() && contract.orElseThrow().compatible()) {
            return factory.createCompatible(
                    verificationRecord(contract.orElseThrow().recordFileName()),
                    located.artifact(),
                    located.classLoader(),
                    contract.orElseThrow());
        }
        return factory.create(verificationRecord(recordFileName), located.artifact(), located.classLoader());
    }

    /**
     * @return whether startup suppression detected safe mode
     */
    boolean safeMode() {
        return safeMode;
    }

    /**
     * @return the directory verification records were extracted into
     */
    Path verificationDirectory() {
        return verificationDirectory;
    }

    /**
     * Extracts an embedded verification record into the verification
     * directory and returns its path. Fail-closed: a missing embedded record
     * aborts the caller's install exactly like the pre-registry agent did.
     *
     * @param fileName the record file packaged under
     *     {@code META-INF/turboism/verification/}
     * @return the extracted record path
     * @throws java.io.IOException when the embedded record is missing
     */
    Path verificationRecord(final String fileName) throws java.io.IOException {
        return extractVerificationRecord(verificationDirectory, fileName);
    }

    /**
     * Extracts {@code fileName} from {@code META-INF/turboism/verification/}
     * into {@code verificationDirectory}. Fail-closed: a missing embedded
     * record aborts the caller.
     */
    static Path extractVerificationRecord(final Path verificationDirectory, final String fileName)
            throws java.io.IOException {
        final String resource = "/META-INF/turboism/verification/" + fileName;
        final Path target =
                verificationDirectory.resolve(fileName).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(target.getParent());
        try (java.io.InputStream source = HookEnvironment.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new java.io.IOException("Embedded Cubism verification record is missing");
            }
            java.nio.file.Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * @return the process classpath used to locate the host artifact in premain
     */
    String classPath() {
        return classPath;
    }

    /**
     * @return the process working directory used to locate the host artifact in premain
     */
    Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * @return the loaded startup policy, {@code null} when unavailable
     */
    RuntimeStartupConfig startupPolicy() {
        return startupPolicy;
    }

    /**
     * Locates the host artifact on the process classpath for premain-phase hooks.
     *
     * @return the host JAR when it can be resolved without loading host classes
     */
    Optional<Path> locateHostArtifact() {
        return StartupSuppressionInstaller.locateHostArtifact(classPath, workingDirectory);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Instrumentation instrumentation;
        private AgentOptions options;
        private HostClassLocator.LocatedHost host;
        private PreviewRuntime runtime;
        private String profile;
        private CompatibilityResolution hostResolution;
        private boolean fullRuntimeAdmission;
        private boolean safeMode;
        private Path verificationDirectory;
        private String classPath = "";
        private Path workingDirectory = Path.of(".");
        private RuntimeStartupConfig startupPolicy;

        Builder instrumentation(final Instrumentation value) {
            this.instrumentation = value;
            return this;
        }

        Builder options(final AgentOptions value) {
            this.options = value;
            return this;
        }

        Builder host(final HostClassLocator.LocatedHost value) {
            this.host = value;
            return this;
        }

        Builder runtime(final PreviewRuntime value) {
            this.runtime = value;
            return this;
        }

        Builder profile(final String value) {
            this.profile = value;
            return this;
        }

        Builder hostResolution(final CompatibilityResolution value) {
            this.hostResolution = value;
            return this;
        }

        Builder fullRuntimeAdmission(final boolean value) {
            this.fullRuntimeAdmission = value;
            return this;
        }

        Builder safeMode(final boolean value) {
            this.safeMode = value;
            return this;
        }

        Builder verificationDirectory(final Path value) {
            this.verificationDirectory = value;
            return this;
        }

        Builder classPath(final String value) {
            this.classPath = value == null ? "" : value;
            return this;
        }

        Builder workingDirectory(final Path value) {
            this.workingDirectory = Objects.requireNonNull(value, "workingDirectory");
            return this;
        }

        Builder startupPolicy(final RuntimeStartupConfig value) {
            this.startupPolicy = value;
            return this;
        }

        HookEnvironment build() {
            return new HookEnvironment(this);
        }
    }
}
