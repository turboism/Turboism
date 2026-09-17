package dev.turboism.shell;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Runtime-owned launcher preference exposed only to the framework shell.
 *
 * <p>The setting selects the executable used on the next managed Cubism launch;
 * it cannot replace the JVM of the current Cubism process. Keeping this service
 * outside {@code PluginContext} prevents third-party plugins from changing the
 * host executable.</p>
 */
public interface CubismJvmSettingsService {

    URI GRAALVM_DOWNLOAD_URI = URI.create("https://www.graalvm.org/downloads/");
    String MANAGED_GRAAL_VERSION = "25.2.4";
    String MANAGED_JAVA_VERSION = "25.0.4";

    /**
     * @return the persisted JVM selection for the next managed launch
     */
    CubismJvm read();

    /**
     * Persists the JVM selection for the next managed launch.
     *
     * @param value the JVM mode to persist
     * @return the persisted value
     */
    CubismJvm save(CubismJvm value);

    /** @return the optional user-configured GraalVM home or Java executable path */
    default String graalVmPath() {
        return "";
    }

    /** Persists an optional user-configured GraalVM home or Java executable path. */
    default String saveGraalVmPath(final String value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** True when the proposed custom path resolves to a GraalVM executable. */
    default boolean graalVmPathCompatible(final String value) {
        return value == null || value.isBlank();
    }

    /** Detects the GraalVM executable the managed launcher can use. */
    default Optional<Path> graalVmJava() {
        return Optional.empty();
    }

    /**
     * Opt-in session preference: when true the session start disables the
     * host's periodic auto-backup via the verified updateSettings path
     * (crash-recovery trades for no mid-edit backup stalls). Off by default.
     */
    default boolean reduceAutoBackup() {
        return false;
    }

    /** Persists the session auto-backup reduction preference. */
    default boolean saveReduceAutoBackup(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * Launcher preference: when true the next managed Cubism launch
     * adds {@code -XX:+UseZGC} to the managed JAVA_TOOL_OPTIONS block.
     * Launch-time flag — takes effect on the next launch only.
     * On by default; users disable it explicitly when startup speed
     * matters more than pause latency.
     */
    default boolean zgc() {
        return true;
    }

    /** Persists the ZGC launcher preference. */
    default boolean saveZgc(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * Launcher preference: when false the next managed Cubism launch adds
     * {@code -Dturboism.optimization.modelUpdateSkip=false} to the managed
     * JAVA_TOOL_OPTIONS block so the unchanged-frame skip hook is not
     * installed. On by default; takes effect on the next launch.
     */
    default boolean modelUpdateSkip() {
        return true;
    }

    /** Returns the default-on uniform-location cache preference for verified hosts. */
    default boolean uniformLocationCache() {
        return true;
    }

    /** Persists the uniform-location cache preference; installation changes require restart. */
    default boolean saveUniformLocationCache(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Persists the unchanged-frame model-update skip preference. */
    default boolean saveModelUpdateSkip(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * Experimental launcher preference: explicit true adds
     * {@code -Dturboism.optimization.incrementalUpdate=true} on the next managed
     * launch. Off by default because complete authoring-write coverage has not
     * been established; enabling requires an explicit user choice.
     */
    default boolean incrementalUpdate() {
        return false;
    }

    /** Persists the per-object incremental model-update preference. */
    default boolean saveIncrementalUpdate(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * @return whether {@link #graalVmJava()} currently resolves an executable
     */
    default boolean graalVmAvailable() {
        return graalVmJava().isPresent();
    }

    /** @return the state of the Turboism-managed runtime, excluding external installations */
    default ManagedRuntimeStatus managedRuntimeStatus() {
        return ManagedRuntimeStatus.unavailable();
    }

    /** Starts one explicit managed-runtime installation. */
    default ManagedRuntimeOperation installManagedRuntime() {
        throw new IllegalStateException("managed GraalVM installation is unavailable");
    }

    /** Revalidates the installed managed runtime and its isolated host. */
    default ManagedRuntimeStatus verifyManagedRuntime() {
        return managedRuntimeStatus();
    }

    /** Removes only the Turboism-managed runtime. */
    default ManagedRuntimeStatus removeManagedRuntime() {
        throw new IllegalStateException("managed GraalVM removal is unavailable");
    }

    /**
     * @return a service reporting the GraalVM default on {@link #read()} and refusing
     *         {@link #save(CubismJvm)} with {@link IllegalStateException}
     */
    static CubismJvmSettingsService unavailable() {
        return new CubismJvmSettingsService() {
            @Override public CubismJvm read() { return CubismJvm.GRAALVM; }
            @Override public CubismJvm save(final CubismJvm value) {
                throw new IllegalStateException("Cubism JVM settings are unavailable");
            }
        };
    }

    /** Lifecycle state of the Turboism-managed GraalVM runtime. */
    enum ManagedRuntimeState {
        /** Nothing is installed. */
        ABSENT,
        /** An install operation is in flight. */
        INSTALLING,
        /** The managed runtime is installed and verified. */
        READY,
        /** The last operation failed. */
        FAILED,
        /** The last operation was cancelled. */
        CANCELLED,
        /** Managed installation is not supported on this runtime. */
        UNSUPPORTED
    }

    /**
     * Point-in-time view of the managed runtime: {@code state}, detected versions and
     * executable, transfer progress in bytes, and a stable diagnostic {@code code} with a
     * human-readable {@code message}. Progress must satisfy
     * {@code 0 <= completedBytes <= totalBytes}.
     */
    record ManagedRuntimeStatus(
        ManagedRuntimeState state,
        String version,
        String javaVersion,
        Optional<Path> javaExecutable,
        long completedBytes,
        long totalBytes,
        String code,
        String message
    ) {
        public ManagedRuntimeStatus {
            state = java.util.Objects.requireNonNull(state, "state");
            version = java.util.Objects.requireNonNullElse(version, "");
            javaVersion = java.util.Objects.requireNonNullElse(javaVersion, "");
            javaExecutable = java.util.Objects.requireNonNull(javaExecutable, "javaExecutable");
            code = java.util.Objects.requireNonNullElse(code, "");
            message = java.util.Objects.requireNonNullElse(message, "");
            if (completedBytes < 0L || totalBytes < 0L || completedBytes > totalBytes) {
                throw new IllegalArgumentException("managed runtime progress is invalid");
            }
        }

        static ManagedRuntimeStatus unavailable() {
            return new ManagedRuntimeStatus(
                ManagedRuntimeState.UNSUPPORTED, "", "", Optional.empty(), 0L, 0L,
                "GRAAL_RUNTIME_UNAVAILABLE", "Managed GraalVM installation is unavailable."
            );
        }
    }

    /** Handle for one in-flight managed-runtime operation. */
    interface ManagedRuntimeOperation {
        /**
         * @return the latest observed status
         */
        ManagedRuntimeStatus status();

        /**
         * @return a stage completing with the terminal status
         */
        CompletionStage<ManagedRuntimeStatus> completion();

        /**
         * @return whether this call requested cancellation before terminal completion
         */
        boolean cancel();
    }

    /** Which JVM the managed launcher should start Cubism on. */
    enum CubismJvm {
        GRAALVM("graalvm"),
        BUNDLED("bundled");

        private final String configValue;

        CubismJvm(final String configValue) {
            this.configValue = configValue;
        }

        /** @return the normalized persisted configuration value */
        public String configValue() {
            return configValue;
        }

        /**
         * Resolves a Cubism JVM mode from persisted configuration.
         *
         * @param value persisted value
         * @return the matching JVM mode
         * @throws IllegalArgumentException when the value is unsupported
         */
        public static CubismJvm fromConfig(final String value) {
            final String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
            for (CubismJvm candidate : values()) {
                if (candidate.configValue.equals(normalized)) return candidate;
            }
            throw new IllegalArgumentException("unsupported Cubism JVM: " + value);
        }
    }
}
