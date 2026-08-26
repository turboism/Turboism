package dev.turboism.plugin.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Runtime-owned launcher preference exposed only to the built-in Core plugin.
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

    CubismJvm read();

    CubismJvm save(CubismJvm value);

    /** Detects the GraalVM executable the managed launcher can use. */
    default Optional<Path> graalVmJava() {
        return Optional.empty();
    }

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

    static CubismJvmSettingsService unavailable() {
        return new CubismJvmSettingsService() {
            @Override public CubismJvm read() { return CubismJvm.GRAALVM; }
            @Override public CubismJvm save(final CubismJvm value) {
                throw new IllegalStateException("Cubism JVM settings are unavailable");
            }
        };
    }

    enum ManagedRuntimeState {
        ABSENT,
        INSTALLING,
        READY,
        FAILED,
        CANCELLED,
        UNSUPPORTED
    }

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

    interface ManagedRuntimeOperation {
        ManagedRuntimeStatus status();
        CompletionStage<ManagedRuntimeStatus> completion();
        boolean cancel();
    }

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
