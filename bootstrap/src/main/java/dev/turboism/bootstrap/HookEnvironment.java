package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.config.RuntimeStartupConfig;
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
 * <p>Hooks never re-derive admission from the artifact themselves; the agent
 * computes the reviewed profile and full-runtime admission once and hands the
 * verdict down, so a contributor cannot widen its own admission.</p>
 */
final class HookEnvironment {

    private final Instrumentation instrumentation;
    private final AgentOptions options;
    private final HostClassLocator.LocatedHost host;
    private final PreviewRuntime runtime;
    private final String profile;
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
     * @return whether the runtime is both reviewed-admitted and full-runtime admitted
     *     for the located host profile
     */
    boolean ordinaryReviewedRuntimeAdmitted() {
        return fullRuntimeAdmission && ReviewedHostArtifacts.admitsFullRuntime(profile);
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
    static Path extractVerificationRecord(
        final Path verificationDirectory,
        final String fileName
    ) throws java.io.IOException {
        final String resource = "/META-INF/turboism/verification/" + fileName;
        final Path target = verificationDirectory.resolve(fileName)
            .toAbsolutePath()
            .normalize();
        java.nio.file.Files.createDirectories(target.getParent());
        try (java.io.InputStream source =
            HookEnvironment.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new java.io.IOException(
                    "Embedded Cubism verification record is missing"
                );
            }
            java.nio.file.Files.copy(
                source,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
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
