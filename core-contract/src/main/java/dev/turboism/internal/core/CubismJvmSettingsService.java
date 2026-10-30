package dev.turboism.internal.core;

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

    /** One optimization's saved preference and separately observed startup state. */
    record OptimizationStatus(
            String key, boolean saved, Optional<Boolean> requested, String source, String state, String reason) {}

    /** Diagnostics for the current session; saved preferences still apply on the next launch. */
    default java.util.List<OptimizationStatus> optimizationStatuses() {
        return java.util.List.of();
    }

    /** One page field's persisted selection, startup request, and separately observed current result. */
    record PerformanceStatus(
            String key, String saved, String requested, String source, String actual, boolean pendingRestart) {}

    /** All fifteen performance fields, with no inferred success for unobserved application. */
    default java.util.List<PerformanceStatus> performanceStatuses() {
        return java.util.List.of();
    }

    /** The canonical global file edited by the settings page, when available. */
    default String globalConfigPath() {
        return "";
    }

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

    /** The immutable auto-backup request captured for this session, before any edits in Settings. */
    default boolean startupReduceAutoBackup() {
        return reduceAutoBackup();
    }

    /** Persists the next session auto-backup reduction preference. */
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
     * Launcher preference: the memory tier applied to the next managed Cubism
     * launch. {@link MemoryProfile#SYSTEM} is the default and keeps the official
     * BAT's {@code -XX:MaxRAMPercentage=100} untouched; the capped tiers append
     * an explicit {@code -Xmx} (and, for the fast-soft variant,
     * {@code -XX:SoftRefLRUPolicyMSPerMB}) to the managed JVM options. An
     * explicit {@code -Xmx} already present in the launch command always wins
     * over the profile. A persisted value the runtime does not recognize reads
     * back as {@link MemoryProfile#SYSTEM}. Launch-time setting — takes effect
     * on the next launch only.
     */
    default MemoryProfile memoryProfile() {
        return MemoryProfile.SYSTEM;
    }

    /** Persists the memory-profile launcher preference. */
    default MemoryProfile saveMemoryProfile(final MemoryProfile value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * Launcher preference: when false the next managed Cubism launch adds
     * {@code -Dturboism.optimization.modelUpdateSkip=false} to the managed
     * JAVA_TOOL_OPTIONS block so the unchanged-frame skip hook is not
     * installed. Off by default; takes effect on the next launch.
     */
    default boolean modelUpdateSkip() {
        return false;
    }

    /** Returns the opt-in uniform-location cache preference for verified hosts. */
    default boolean uniformLocationCache() {
        return false;
    }

    /**
     * Launcher preference: when true the next managed Cubism launch enables
     * the skipped-frame buffer-upload elision hook (content compare, still
     * gated on skipped frames). Off by default on every platform; an explicit
     * true opts in. Takes effect on the next launch.
     */
    default boolean uploadElision() {
        return false;
    }

    /** Persists the upload-elision preference; installation changes require restart. */
    default boolean saveUploadElision(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * Launcher preference: when true the next managed Cubism launch enables
     * the input-path elision hook (redundant focus/cursor forwards are
     * short-circuited before they reach the native query). The elided path
     * only exists under Wine/Proton; the unset default is off everywhere; an explicit value always wins. Takes effect on
     * the next launch.
     */
    default boolean inputPathElision() {
        return false;
    }

    /** Persists the input-path elision preference; installation changes require restart. */
    default boolean saveInputPathElision(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /**
     * Launcher preference: when explicitly enabled under Linux/Proton, {@code mesa_glthread=true} is exported and
     * {@code -Dturboism.optimization.mesaGlThread=true} is added, so Mesa
     * submits GL work from a dedicated thread while the deferred error-check
     * hook keeps the shader helper's per-call {@code glGetError} from
     * flushing it. The pair is one combined option — Mesa threading without
     * deferred checking was measured a regression. On native Windows the
     * default is also off and launches ignore it entirely; an explicit
     * value always wins. Requires Mesa. Takes effect on next launch.
     */
    default boolean mesaGlThread() {
        return false;
    }

    /** Persists the mesa-gl-thread preference; installation changes require restart. */
    default boolean saveMesaGlThread(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
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
     * Launcher preference: when true the next managed Cubism launch enables
     * the verified extended interpolation optimization (ArtMesh, Warp deformer,
     * and Rotation deformer forms under Ellipse and SNS curves). Off by default;
     * takes effect on the next launch.
     */
    default boolean meshInterpolation() {
        return false;
    }

    /** Persists the extended interpolation preference; installation changes require restart. */
    default boolean saveMeshInterpolation(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Launcher preference: per-shader uniform location cache (premain hook). Off by default. */
    default boolean shaderUniform() {
        return false;
    }

    /** Persists the per-shader uniform location cache preference; installation changes require restart. */
    default boolean saveShaderUniform(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Launcher preference: invocation-local matrix scratch storage in GTransform. Off by default. */
    default boolean matrixScratch() {
        return false;
    }

    /** Persists the matrix scratch preference; installation changes require restart. */
    default boolean saveMatrixScratch(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Launcher preference: reuse unchanged PNG archives during saving. Off by default. */
    default boolean imageArchiveReuse() {
        return false;
    }

    /** Persists the image-archive reuse preference; installation changes require restart. */
    default boolean saveImageArchiveReuse(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Launcher preference: cache float-array parsing during model serialization. Off by default. */
    default boolean floatArrayParseCache() {
        return false;
    }

    /** Persists the float-array parse cache preference; installation changes require restart. */
    default boolean saveFloatArrayParseCache(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Launcher preference: accelerate texture upload pixel preparation (bundled Java 17 only). Off by default. */
    default boolean textureUploadPreparation() {
        return false;
    }

    /** Persists the texture-upload preparation preference; installation changes require restart. */
    default boolean saveTextureUploadPreparation(final boolean value) {
        throw new IllegalStateException("Cubism JVM settings are unavailable");
    }

    /** Launcher preference: accelerate warp deformer position projection (bundled Java 17 only). Off by default. */
    default boolean warpPositionProjection() {
        return false;
    }

    /** Persists the warp-position projection preference; installation changes require restart. */
    default boolean saveWarpPositionProjection(final boolean value) {
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
            @Override
            public CubismJvm read() {
                return CubismJvm.GRAALVM;
            }

            @Override
            public CubismJvm save(final CubismJvm value) {
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
            String message) {
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
                    ManagedRuntimeState.UNSUPPORTED,
                    "",
                    "",
                    Optional.empty(),
                    0L,
                    0L,
                    "GRAAL_RUNTIME_UNAVAILABLE",
                    "Managed GraalVM installation is unavailable.");
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
            final String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (CubismJvm candidate : values()) {
                if (candidate.configValue.equals(normalized)) return candidate;
            }
            throw new IllegalArgumentException("unsupported Cubism JVM: " + value);
        }
    }

    /**
     * Which heap/soft-cache tier the managed launcher applies to the next
     * Cubism start. {@link #SYSTEM} emits no extra option, so the official BAT
     * keeps sizing the heap with {@code -XX:MaxRAMPercentage=100}.
     * {@link #BALANCED_4G} sets an explicit {@code -Xmx4g}; under a bounded heap
     * the collector can finally trigger the host's existing soft-reference
     * cache release. {@link #BALANCED_4G_FAST_SOFT} additionally shortens the
     * soft-reference LRU clock via {@code -XX:SoftRefLRUPolicyMSPerMB=100}.
     * The tiers compose with the ZGC toggle and never override an explicit
     * {@code -Xmx} already present in the launch command.
     */
    enum MemoryProfile {
        SYSTEM("system"),
        BALANCED_4G("balanced4g"),
        BALANCED_4G_FAST_SOFT("balanced4gFastSoft");

        private final String configValue;

        MemoryProfile(final String configValue) {
            this.configValue = configValue;
        }

        /** @return the normalized persisted configuration value */
        public String configValue() {
            return configValue;
        }

        /**
         * Resolves a memory profile from persisted configuration.
         *
         * @param value persisted value
         * @return the matching profile
         * @throws IllegalArgumentException when the value is unsupported
         */
        public static MemoryProfile fromConfig(final String value) {
            final String normalized = value == null ? "" : value.trim();
            for (MemoryProfile candidate : values()) {
                if (candidate.configValue.equals(normalized)) return candidate;
            }
            throw new IllegalArgumentException("unsupported memory profile: " + value);
        }

        /**
         * Fail-closed resolution for reads: an absent, blank, or unrecognized
         * persisted value yields {@link #SYSTEM} rather than an exception, so a
         * corrupt preference can never widen the heap policy beyond stock.
         */
        public static MemoryProfile fromConfigOrSystem(final String value) {
            try {
                return fromConfig(value);
            } catch (IllegalArgumentException unsupported) {
                return SYSTEM;
            }
        }
    }
}
